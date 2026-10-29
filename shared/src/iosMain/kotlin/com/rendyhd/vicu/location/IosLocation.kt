package com.rendyhd.vicu.location

import com.rendyhd.vicu.notification.IosNotificationIds
import com.rendyhd.vicu.util.LocalNotification
import com.rendyhd.vicu.util.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLPlacemark
import platform.Foundation.NSURL
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKLocalSearch
import platform.MapKit.MKLocalSearchRequest
import platform.MapKit.MKMapItem
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * What only Swift can do for location reminders: watch regions with CLMonitor (a Swift-only API)
 * and answer CLLocationManager, whose delegate Kotlin cannot subclass (iosApp/Vicu/LocationMonitor.swift).
 */
interface AppleLocationBridge {
    /** Watches exactly [regions]; Swift reports entering and leaving to `IosApp.onRegionEvent`. */
    fun applyRegions(regions: List<LocationRegion>)

    /** 0: not allowed (or not asked yet), 1: while using the app, 2: always. */
    fun authorization(): Int

    /** Shows the system prompt: while using first, then Always. */
    fun requestAuthorization()

    /** One position, or null; [completion] runs once. */
    fun currentLocation(completion: (GeoPoint?) -> Unit)
}

/** Set by IosApp.start once Swift hands the bridge over; null in tests and before that. */
class IosLocationBridgeHolder {
    var bridge: AppleLocationBridge? = null
}

class IosGeofencer(private val holder: IosLocationBridgeHolder) : PlatformGeofencer {
    override val capacity: Int = LocationRegionPlanner.IOS_CAPACITY

    override suspend fun apply(regions: List<LocationRegion>) {
        val bridge = holder.bridge ?: return
        dispatch_async(dispatch_get_main_queue()) { bridge.applyRegions(regions) }
    }
}

/**
 * The location permission on iOS, read from the bridge. Building it touches nothing (gotcha 19);
 * [refresh] runs at start, when the app comes to the front and when the authorization changes.
 */
class IosLocationPermissionPlatform(private val holder: IosLocationBridgeHolder) : LocationPermissionPlatform {
    private val _access = MutableStateFlow(LocationAccess.NONE)
    override val access: StateFlow<LocationAccess> = _access.asStateFlow()

    override fun refresh() {
        val bridge = holder.bridge ?: return
        _access.value = when (bridge.authorization()) {
            2 -> LocationAccess.ALWAYS
            1 -> LocationAccess.WHILE_USING
            else -> LocationAccess.NONE
        }
    }

    override fun request(): Boolean {
        val bridge = holder.bridge ?: return false
        dispatch_async(dispatch_get_main_queue()) { bridge.requestAuthorization() }
        return true
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
    }
}

class IosDeviceLocation(private val holder: IosLocationBridgeHolder) : DeviceLocation {
    override suspend fun current(): GeoPoint? {
        val bridge = holder.bridge ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                dispatch_async(dispatch_get_main_queue()) {
                    bridge.currentLocation { point -> if (continuation.isActive) continuation.resume(point) }
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** Place search with Apple Maps (MKLocalSearch) and CLGeocoder; no delegates involved. */
@OptIn(ExperimentalForeignApi::class)
class IosPlaceSearch : PlaceSearch {
    override val attribution: String? = null

    override suspend fun search(query: String, near: GeoPoint?): List<PlaceSuggestion> =
        suspendCancellableCoroutine { continuation ->
            val request = MKLocalSearchRequest().apply {
                naturalLanguageQuery = query
                near?.let {
                    setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(it.latitude, it.longitude), SEARCH_SPAN_M, SEARCH_SPAN_M))
                }
            }
            val search = MKLocalSearch(request)
            continuation.invokeOnCancellation { search.cancel() }
            search.startWithCompletionHandler { response, error ->
                if (!continuation.isActive) return@startWithCompletionHandler
                if (response == null) {
                    // MKErrorPlacemarkNotFound is "no results", not a failure.
                    if (error?.code == PLACEMARK_NOT_FOUND) {
                        continuation.resume(emptyList())
                    } else {
                        continuation.resumeWith(Result.failure(IllegalStateException(error?.localizedDescription ?: "Search failed")))
                    }
                    return@startWithCompletionHandler
                }
                val places = response.mapItems.filterIsInstance<MKMapItem>().mapNotNull { item ->
                    val placemark = item.placemark
                    val (lat, lon) = placemark.coordinate.useContents { latitude to longitude }
                    val name = item.name ?: placemark.name ?: return@mapNotNull null
                    PlaceSuggestion(name, address(placemark, name), lat, lon)
                }
                continuation.resume(places)
            }
        }

    override suspend fun reverse(point: GeoPoint): PlaceSuggestion? = suspendCancellableCoroutine { continuation ->
        val geocoder = CLGeocoder()
        continuation.invokeOnCancellation { geocoder.cancelGeocode() }
        geocoder.reverseGeocodeLocation(CLLocation(point.latitude, point.longitude)) { placemarks, _ ->
            if (!continuation.isActive) return@reverseGeocodeLocation
            val placemark = placemarks?.firstOrNull() as? CLPlacemark
            val name = placemark?.name ?: placemark?.thoroughfare
            continuation.resume(
                if (placemark == null || name == null) null else PlaceSuggestion(name, address(placemark, name), point.latitude, point.longitude),
            )
        }
    }

    private fun address(placemark: CLPlacemark, name: String): String {
        val street = StreetAddress.line(placemark.thoroughfare, placemark.subThoroughfare, placemark.ISOcountryCode)
        val town = listOfNotNull(placemark.postalCode, placemark.locality).joinToString(" ")
        return listOf(street, town, placemark.country.orEmpty())
            .filter { it.isNotBlank() && it != name }
            .distinct()
            .joinToString(", ")
    }

    private companion object {
        const val SEARCH_SPAN_M = 50_000.0

        // MKError.placemarkNotFound
        const val PLACEMARK_NOT_FOUND = 4L
    }
}

/**
 * Shows a fired location reminder at once, as a task reminder (Mark Complete, Snooze). The id
 * holds the task id, so a second one for the same task replaces the first.
 */
class IosLocationNotifier : LocationNotifier {
    override fun show(taskId: Long, title: String, body: String, sound: Boolean) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(if (sound) UNNotificationSound.defaultSound else null)
            setCategoryIdentifier(IosNotificationIds.CATEGORY_TASK)
            setThreadIdentifier("reminders")
            setUserInfo(
                mapOf(
                    LocalNotification.KEY_KIND to KIND,
                    LocalNotification.KEY_TASK_ID to taskId.toString(),
                    LocalNotification.KEY_TITLE to title,
                ),
            )
        }
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(idFor(taskId), content, trigger = null),
        ) { error -> error?.let { Logger.w("IosLocationNotifier", "Could not show a location reminder: ${it.localizedDescription}") } }
    }

    companion object {
        /** The notification kind in userInfo; not a planned LocalNotification, it is shown at once. */
        const val KIND = "LOCATION"

        fun idFor(taskId: Long) = "location:$taskId"
    }
}
