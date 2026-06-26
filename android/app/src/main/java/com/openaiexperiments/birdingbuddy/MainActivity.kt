package com.openaiexperiments.birdingbuddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyActions
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionService
import com.openaiexperiments.birdingbuddy.nativeapp.ui.OaiVoiceNativeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BirdBuddySessionService.enqueueAction(this, BirdBuddyActions.ACTION_ARM)

        setContent {
            OaiVoiceNativeApp()
        }
    }
}
