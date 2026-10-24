package dev.rtxbb.weather.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.rtxbb.weather.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class Hour(val time: String, val temp: Int, val code: Int, val isDay: Boolean)

data class Weather(
    val city: String,
    val temp: Int,
    val feelsLike: Int,
    val code: Int,
    val isDay: Boolean,
    val min: Int,
    val max: Int,
    val hourly: List<Hour>,
)

data class Place(val lat: Double, val lon: Double, val name: String)

object OpenMeteo {
    suspend fun fetchJson(lat: Double, lon: Double): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto"
    )

    suspend fun searchCity(name: String): Place? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val lang = Locale.getDefault().language
        val results = JSONObject(
            get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=$lang")
        ).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return Place(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name"))
    }

    /** Parses the forecast JSON; `city` is our own field added before caching. */
    fun parse(json: String): Weather {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val d = o.getJSONObject("daily")
        val times = h.getJSONArray("time")
        val temps = h.getJSONArray("temperature_2m")
        val codes = h.getJSONArray("weather_code")
        val days = h.getJSONArray("is_day")
        // Both are local ISO "yyyy-MM-ddTHH:mm", so string order is time order.
        val now = cur.getString("time")
        val start = (0 until times.length()).firstOrNull { times.getString(it) > now } ?: times.length()
        val hourly = (start until minOf(start + 6, times.length())).map {
            Hour(times.getString(it).substringAfter('T'), temps.getDouble(it).roundToInt(), codes.getInt(it), days.getInt(it) == 1)
        }
        return Weather(
            city = o.optString("city"),
            temp = cur.getDouble("temperature_2m").roundToInt(),
            feelsLike = cur.getDouble("apparent_temperature").roundToInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.getInt("is_day") == 1,
            min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            hourly = hourly,
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

// WMO weather interpretation codes: https://open-meteo.com/en/docs
@DrawableRes
fun weatherIcon(code: Int, isDay: Boolean): Int = when (code) {
    0 -> if (isDay) R.drawable.ic_wx_clear_day else R.drawable.ic_wx_clear_night
    1, 2 -> if (isDay) R.drawable.ic_wx_partly_day else R.drawable.ic_wx_partly_night
    3 -> R.drawable.ic_wx_cloudy
    45, 48 -> R.drawable.ic_wx_fog
    in 71..77, 85, 86 -> R.drawable.ic_wx_snow
    in 95..99 -> R.drawable.ic_wx_thunder
    else -> R.drawable.ic_wx_rain
}

@StringRes
fun weatherLabel(code: Int): Int = when (code) {
    0 -> R.string.wx_clear
    1, 2 -> R.string.wx_partly
    3 -> R.string.wx_cloudy
    45, 48 -> R.string.wx_fog
    in 71..77, 85, 86 -> R.string.wx_snow
    in 95..99 -> R.string.wx_thunder
    else -> R.string.wx_rain
}
