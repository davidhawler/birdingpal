package com.openaiexperiments.birdingbuddy.nativeapp.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BirdBuddyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val armed = prefs.getBoolean(KEY_ARMED, true)
        if (!armed) {
            return
        }

        BirdBuddySessionService.enqueueAction(context, BirdBuddyActions.ACTION_ARM)
    }

    companion object {
        private const val PREFS_NAME = "bird_buddy_service_prefs"
        private const val KEY_ARMED = "armed"
    }
}
