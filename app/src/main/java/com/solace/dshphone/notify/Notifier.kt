package com.solace.dshphone.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Fixed notification slots: progress, reminders and input requests live in
 * separate channels so they never push each other out. The lockscreen shows
 * only the channel title — never task content.
 */
object Notifier {

    const val CH_PROGRESS = "progress"
    const val CH_REMINDER = "reminder"
    const val CH_INPUT = "input"
    const val CH_GROUP = "grouped"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "任务进度", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "定时与提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_INPUT, "需要你处理", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun progress(context: Context, title: String, text: String, id: Int = 1001) =
        post(context, CH_PROGRESS, title, text, id, ongoing = true)

    fun reminder(context: Context, title: String, text: String, id: Int = 1002) =
        post(context, CH_REMINDER, title, text, id, ongoing = false)

    fun needsInput(context: Context, title: String, text: String, id: Int = 1003, jumpTo: Class<*>? = null) =
        post(context, CH_INPUT, title, text, id, ongoing = false, jump = jumpTo)

    fun groupSummary(context: Context, count: Int) =
        post(context, CH_GROUP.ifEmpty { CH_PROGRESS }, "DSH-phone", "有 $count 个任务在后台运行", 1004, ongoing = true)

    private fun post(
        context: Context,
        channel: String,
        title: String,
        text: String,
        id: Int,
        ongoing: Boolean,
        jump: Class<*>? = null,
    ) {
        val intent = Intent(context, jump ?: context.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setContentIntent(pi)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        }
    }
}
