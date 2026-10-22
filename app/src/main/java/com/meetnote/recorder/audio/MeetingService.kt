package com.meetnote.recorder.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.meetnote.recorder.MainActivity
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Timer
import java.util.TimerTask

class MeetingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: Recorder = MediaRecorderEngine()
    private var ticker: Timer? = null
    private var meetingId: Long = 0L
    private var audioFile: File? = null
    private var startedAtElapsed: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getLongExtra(EXTRA_MEETING_ID, -1L))
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(id: Long) {
        if (id <= 0) {
            stopSelf()
            return
        }
        meetingId = id
        val repository = DefaultMeetingRepository(
            dao = AppDatabase.get(this).meetingDao(),
            fileStore = FileStore(filesDir),
        )
        val file = repository.meetingAudioFile(id)
        audioFile = file

        try {
            recorder.start(file)
        } catch (e: Exception) {
            RecordingState.onError(e.message ?: "无法开始录音")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        startForegroundWithNotification()
        RecordingState.bind(meetingId = id, durationMs = 0L)
        RecordingState.onStart()
        startedAtElapsed = SystemClock.elapsedRealtime()

        ticker?.cancel()
        ticker = Timer("meeting-ticker", true).also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    RecordingState.onTick(SystemClock.elapsedRealtime() - startedAtElapsed)
                }
            }, 1_000L, 1_000L)
        }
    }

    private fun handleStop() {
        val duration = recorder.stop()
        ticker?.cancel()
        ticker = null
        RecordingState.onStop(durationMs = duration)

        val repository = DefaultMeetingRepository(
            dao = AppDatabase.get(this).meetingDao(),
            fileStore = FileStore(filesDir),
        )
        scope.launch {
            repository.finishMeeting(
                id = meetingId,
                audioPath = audioFile?.absolutePath,
                durationMs = duration,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 进程被杀等意外停止：尽力把已录时长落库
        if (recorder.isRunning()) {
            val duration = recorder.stop()
            ticker?.cancel()
            RecordingState.onStop(durationMs = duration)
            val repository = DefaultMeetingRepository(
                dao = AppDatabase.get(this).meetingDao(),
                fileStore = FileStore(filesDir),
            )
            scope.launch {
                repository.finishMeeting(
                    id = meetingId,
                    audioPath = audioFile?.absolutePath,
                    durationMs = duration,
                )
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "会议录音",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "会议进行中的常驻通知" }
            )
        }
    }

    private fun startForegroundWithNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeetingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("会议记录中")
            .setContentText("录音进行中，返回应用可查看拍摄进度")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "停止录音", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.meetnote.recorder.action.START"
        const val ACTION_STOP = "com.meetnote.recorder.action.STOP"
        const val EXTRA_MEETING_ID = "com.meetnote.recorder.extra.MEETING_ID"
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, meetingId: Long) {
            val intent = Intent(context, MeetingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeetingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
