package com.openaiexperiments.birdingbuddy.nativeapp.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BirdBuddyNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            BirdBuddyActions.ACTION_NOTIFICATION_ARM -> {
                BirdBuddySessionService.enqueueAction(context, BirdBuddyActions.ACTION_ARM)
            }

            BirdBuddyActions.ACTION_NOTIFICATION_DISARM -> {
                BirdBuddySessionService.enqueueAction(context, BirdBuddyActions.ACTION_DISARM)
            }

            BirdBuddyActions.ACTION_NOTIFICATION_START_ANALYZE -> {
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_START_ANALYZE_10S,
                    triggerSource = BirdBuddyTriggerSource.NOTIFICATION
                )
            }
        }
    }
}
