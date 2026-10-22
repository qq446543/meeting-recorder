package com.meetnote.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meetnote.recorder.ui.nav.AppNav
import com.meetnote.recorder.ui.theme.MeetingRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeetingRecorderTheme {
                AppNav()
            }
        }
    }
}
