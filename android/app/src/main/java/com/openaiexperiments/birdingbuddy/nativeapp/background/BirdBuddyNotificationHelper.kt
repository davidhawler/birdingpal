package com.openaiexperiments.birdingbuddy.nativeapp.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openaiexperiments.birdingbuddy.MainActivity
import com.openaiexperiments.birdingbuddy.R

object BirdBuddyNotificationHelper {
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)

        val runtime =
            NotificationChannel(
                BIRD_BUDDY_CHANNEL_ID,
                "Bird Buddy Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent Bird Buddy runtime controls."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

        val alerts =
            NotificationChannel(
                BIRD_BUDDY_ALERT_CHANNEL_ID,
                "Bird Buddy Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Actionable notifications when user attention is required."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

        manager.createNotificationChannel(runtime)
        manager.createNotificationChannel(alerts)
    }

    fun buildPersistentNotification(
        context: Context,
        state: BirdBuddySessionState
    ): Notification {
        val analyzeIntent =
            buildReceiverPendingIntent(
                context = context,
                action = BirdBuddyActions.ACTION_NOTIFICATION_START_ANALYZE,
                requestCode = 51
            )

        val armOrDisarmAction =
            if (state.armed) {
                NotificationCompat.Action(
                    R.drawable.mic,
                    "Disarm",
                    buildReceiverPendingIntent(
                        context,
                        BirdBuddyActions.ACTION_NOTIFICATION_DISARM,
                        requestCode = 52
                    )
                )
            } else {
                NotificationCompat.Action(
                    R.drawable.mic,
                    "Arm",
                    buildReceiverPendingIntent(
                        context,
                        BirdBuddyActions.ACTION_NOTIFICATION_ARM,
                        requestCode = 53
                    )
                )
            }

        val contentIntent =
            PendingIntent.getActivity(
                context,
                54,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val bleSummary =
            if (state.bleConnected) {
                "BLE connected"
            } else {
                "BLE disconnected"
            }

        return NotificationCompat.Builder(context, BIRD_BUDDY_CHANNEL_ID)
            .setSmallIcon(R.drawable.mic)
            .setContentTitle("Bird Buddy Background")
            .setContentText("${state.statusText} • $bleSummary")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${state.statusText}\n$bleSummary\n${state.bleStatusText}"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.bird_1,
                    "Analyze (10s)",
                    analyzeIntent
                )
            )
            .addAction(armOrDisarmAction)
            .build()
    }

    fun postActionableNotification(context: Context, reason: String) {
        val analyzeIntent =
            buildReceiverPendingIntent(
                context = context,
                action = BirdBuddyActions.ACTION_NOTIFICATION_START_ANALYZE,
                requestCode = 55
            )

        val notification =
            NotificationCompat.Builder(context, BIRD_BUDDY_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.mic)
                .setContentTitle("Bird Buddy action needed")
                .setContentText(reason)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .addAction(
                    NotificationCompat.Action(
                        R.drawable.bird_1,
                        "Start Analyze",
                        analyzeIntent
                    )
                )
                .build()

        NotificationManagerCompat.from(context).notify(BIRD_BUDDY_ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildReceiverPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, BirdBuddyNotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
