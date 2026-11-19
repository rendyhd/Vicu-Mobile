package com.rendyhd.vicu.permission

import kotlin.concurrent.Volatile
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSetting
import platform.UserNotifications.UNNotificationSettingEnabled
import platform.UserNotifications.UNNotificationSettingNotSupported
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The notification permission on iOS. The system answers asynchronously while the shared code
 * asks synchronously, so the answer is cached: [refresh] reads it at start and each time the app
 * comes to the front (IosAppLifecycle). Building this does not touch the notification center, which
 * exists only in a running app. iOS shows its prompt only once; after that the settings page is
 * the way back. The request includes badges, for the count on the app icon (TodayBadge); a
 * permission given before Vicu asked for badges is asked for them again when that setting is
 * turned on ([requestBadges]).
 */
class IosNotificationPermissionPlatform : NotificationPermissionPlatform {

    @Volatile
    private var status: UNAuthorizationStatus = UNAuthorizationStatusNotDetermined

    @Volatile
    private var badge: UNNotificationSetting = UNNotificationSettingNotSupported

    override val needsRuntimePermission: Boolean = true

    override fun isGranted(): Boolean = status == UNAuthorizationStatusAuthorized ||
        status == UNAuthorizationStatusProvisional ||
        status == UNAuthorizationStatusEphemeral

    override fun launchSystemRequest(): Boolean {
        if (status != UNAuthorizationStatusNotDetermined) {
            openSystemSettings()
            return true
        }
        requestAuthorization()
        return true
    }

    override fun badgesAllowed(): Boolean = badge == UNNotificationSettingEnabled

    /**
     * Once the prompt was answered iOS does not show it again, but a request is still how an app
     * that was allowed notifications before it asked for badges adds them; the answer is read back.
     */
    override fun requestBadges() {
        if (status == UNAuthorizationStatusNotDetermined) return
        requestAuthorization()
    }

    private fun requestAuthorization() {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { _, _ -> refresh() }
    }

    override fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
    }

    /** Reads the permission again; call when the app comes to the front. */
    fun refresh() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            if (settings != null) {
                status = settings.authorizationStatus
                badge = settings.badgeSetting
            }
        }
    }
}
