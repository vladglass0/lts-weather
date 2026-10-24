package dev.rtxbb.weather.data

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** The widget place ("selected"), the last GPS fix and the searched places. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
    private fun Place.json() = JSONObject().put("lat", lat).put("lon", lon).put("name", name)
    private fun JSONObject.place() = Place(getDouble("lat"), getDouble("lon"), getString("name"))
    private fun get(ctx: Context, key: String) = sp(ctx).getString(key, null)?.let { JSONObject(it).place() }
    private fun put(ctx: Context, key: String, p: Place) = sp(ctx).edit { putString(key, p.json().toString()) }

    fun load(ctx: Context) = get(ctx, "selected")
    fun save(ctx: Context, p: Place) = put(ctx, "selected", p)
    fun gps(ctx: Context) = get(ctx, "gps")
    fun saveGps(ctx: Context, p: Place) = put(ctx, "gps", p)

    fun saved(ctx: Context): List<Place> {
        val a = JSONArray(sp(ctx).getString("saved", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).place() }
    }

    fun addSaved(ctx: Context, p: Place) {
        val list = listOf(p) + saved(ctx).filterNot { it.name == p.name }
        sp(ctx).edit { putString("saved", JSONArray(list.map { it.json() }).toString()) }
    }
}

/** Foreground-only lookup: widgets reuse the saved place, so no background location permission. */
@RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
suspend fun currentPlace(ctx: Context): Place? {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { lm.isProviderEnabled(it) }
    val loc = provider?.let {
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                lm.getCurrentLocation(it, null, ctx.mainExecutor) { l -> cont.resume(l) }
            }
        } ?: lm.getLastKnownLocation(it)
    } ?: return null

    val name = if (Geocoder.isPresent()) withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<String?> { cont ->
            Geocoder(ctx).getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(list: MutableList<android.location.Address>) {
                    val a = list.firstOrNull()
                    cont.resume(a?.locality ?: a?.subAdminArea ?: a?.adminArea)
                }

                override fun onError(errorMessage: String?) = cont.resume(null)
            })
        }
    } else null
    return Place(loc.latitude, loc.longitude, name ?: "%.2f, %.2f".format(loc.latitude, loc.longitude))
}
