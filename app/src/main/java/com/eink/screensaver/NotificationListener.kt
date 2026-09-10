package com.eink.screensaver

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/** What the lock screen draws in its top-left corner. */
data class NotificationCounts(val sms: Int, val telegram: Int, val missedCalls: Int) {
    val isEmpty: Boolean get() = sms <= 0 && telegram <= 0 && missedCalls <= 0
}

/**
 * Counts the only three kinds of notification this screen shows — SMS, Telegram
 * and missed calls — and nothing else. It reads titles and bodies of no
 * notification: only the posting package, the category and the message count.
 *
 * The counts are written to prefs on every change rather than broadcast at the
 * lock screen. A notification arriving is not worth a panel refresh on its own
 * — the number is picked up by the next clock tick, which is redrawing anyway.
 *
 * Notification access is a separate user grant in Settings, and on Android 13+
 * a sideloaded app needs *Allow restricted settings* before the toggle sticks,
 * exactly like [SleepAccessibilityService]. Without it this service never binds
 * and the corner stays empty.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"

        /** AOSP posts the missed-call notification from Telecom, not the dialer. */
        private const val TELECOM_PACKAGE = "com.android.server.telecom"

        /** Telegram X, and the plain client is matched by prefix. */
        private const val TELEGRAM_X_PACKAGE = "org.thunderdog.challegram"

        /**
         * Fallback for when [Telephony.Sms.getDefaultSmsPackage] gives nothing,
         * which is what a device with no telephony does.
         */
        private val KNOWN_SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )

        @Volatile
        private var instance: NotificationListener? = null

        /**
         * What to draw. Recounted from the live service when it is bound, so a
         * notification dismissed on another device is not left on the panel;
         * otherwise the last count the service wrote, which is what the screen
         * was showing anyway.
         */
        fun snapshot(context: Context): NotificationCounts {
            val live = instance?.count()
            if (live != null) {
                PrefsManager.setNotificationCounts(context, live)
                return live
            }
            return PrefsManager.getNotificationCounts(context)
        }

        /**
         * Whether the user has granted notification access. For the settings
         * screen, which has to report something before the service ever binds.
         */
        fun isEnabledInSettings(context: Context): Boolean = try {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "could not read enabled listeners: ${e.message}")
            false
        }
    }

    private enum class Kind { SMS, TELEGRAM, MISSED_CALL }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "connected")
        persist()
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.d(TAG, "disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = persist()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = persist()

    private fun persist() {
        val counts = count() ?: return
        PrefsManager.setNotificationCounts(this, counts)
    }

    /** Null when the service is bound but the system refuses the query. */
    private fun count(): NotificationCounts? = try {
        val smsPackage = defaultSmsPackage()
        var sms = 0
        var telegram = 0
        var missed = 0
        for (sbn in activeNotifications.orEmpty()) {
            when (kindOf(sbn, smsPackage)) {
                Kind.SMS -> sms += weight(sbn)
                Kind.TELEGRAM -> telegram += weight(sbn)
                Kind.MISSED_CALL -> missed += weight(sbn)
                null -> {}
            }
        }
        NotificationCounts(sms, telegram, missed)
    } catch (e: Throwable) {
        Log.w(TAG, "could not read active notifications: ${e.message}")
        null
    }

    private fun kindOf(sbn: StatusBarNotification, smsPackage: String?): Kind? {
        val n = sbn.notification ?: return null

        // The header of a bundle, counted again by the children under it.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        // A call in progress, a media player, a sync — anything permanent is
        // not something the user has missed.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null

        if (n.category == Notification.CATEGORY_MISSED_CALL) return Kind.MISSED_CALL

        val pkg = sbn.packageName ?: return null
        if (pkg == TELECOM_PACKAGE) return Kind.MISSED_CALL
        if (pkg.startsWith("org.telegram") || pkg == TELEGRAM_X_PACKAGE) return Kind.TELEGRAM
        if (pkg == smsPackage || pkg in KNOWN_SMS_PACKAGES) return Kind.SMS
        return null
    }

    /**
     * How many missed things one notification stands for. A messaging app
     * bundles a chat into a single notification, so the count has to come out
     * of it: MessagingStyle carries one entry per message, and `number` is what
     * an app that sets a badge count uses. Neither is mandatory — one is the
     * floor.
     */
    private fun weight(sbn: StatusBarNotification): Int {
        val n = sbn.notification ?: return 1
        @Suppress("DEPRECATION")
        val messages = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0
        @Suppress("DEPRECATION")
        return maxOf(messages, n.number, 1)
    }

    private fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(this)
    } catch (e: Throwable) {
        Log.d(TAG, "no default SMS package: ${e.message}")
        null
    }
}
