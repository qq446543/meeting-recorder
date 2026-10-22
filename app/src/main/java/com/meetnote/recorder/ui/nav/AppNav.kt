package com.meetnote.recorder.ui.nav

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import com.meetnote.recorder.ui.capture.CaptureScreen
import com.meetnote.recorder.ui.detail.MeetingDetailScreen
import com.meetnote.recorder.ui.guide.GuideScreen
import com.meetnote.recorder.ui.list.MeetingListScreen
import com.meetnote.recorder.ui.list.MeetingListViewModel

object Routes {
    const val LIST = "list"
    const val GUIDE = "guide"
    const val CAPTURE = "capture/{meetingId}"
    const val DETAIL = "detail/{meetingId}"
    fun capture(meetingId: Long) = "capture/$meetingId"
    fun detail(meetingId: Long) = "detail/$meetingId"
}

/** 全局唯一仓库，避免各屏各建一套 */
fun provideRepository(context: Context): com.meetnote.recorder.data.MeetingRepository =
    DefaultMeetingRepository(
        dao = AppDatabase.get(context).meetingDao(),
        fileStore = FileStore(context.filesDir),
    )

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = androidx.compose.runtime.remember { provideRepository(context) }

    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            val vm: MeetingListViewModel = viewModel(factory = object :
                androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MeetingListViewModel(repository) as T
            })
            MeetingListScreen(
                meetings = vm.meetings,
                navigateTo = vm.navigateTo,
                onConsumeNavigation = vm::consumeNavigation,
                onOpenDetail = { navController.navigate(Routes.detail(it)) },
                onOpenCapture = { navController.navigate(Routes.capture(it)) },
                onCreateMeeting = vm::createMeeting,
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
            )
        }

        composable(
            Routes.CAPTURE,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType }),
        ) { entry ->
            val meetingId = entry.arguments!!.getLong("meetingId")
            CaptureScreen(
                meetingId = meetingId,
                repository = repository,
                onFinished = {
                    navController.navigate(Routes.detail(meetingId)) {
                        popUpTo(Routes.LIST)
                    }
                },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
            )
        }

        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType }),
        ) { entry ->
            val meetingId = entry.arguments!!.getLong("meetingId")
            MeetingDetailScreen(
                meetingId = meetingId,
                repository = repository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(onBack = { navController.popBackStack() })
        }
    }
}
