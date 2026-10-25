package dev.rtxbb.weather.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.rtxbb.weather.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp
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
    suspend fun fetchJson(lat: Double, lon: Double): String = try {
        get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto"
        )
    } catch (e: IOException) {
        // api.open-meteo.com is unreachable from some ISPs; MET Norway usually is.
        try {
            fromMet(get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f".format(Locale.US, lat, lon)))
        } catch (e2: Exception) {
            e2.addSuppressed(e)
            throw e2
        }
    }

    /** Converts a MET Norway locationforecast/compact response into the Open-Meteo shape [parse] reads. */
    fun fromMet(json: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val series = JSONObject(json).getJSONObject("properties").getJSONArray("timeseries")
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val times = JSONArray(); val temps = JSONArray(); val codes = JSONArray(); val days = JSONArray()
        var current: JSONObject? = null
        var today = ""
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (i in 0 until series.length()) {
            val s = series.getJSONObject(i)
            val data = s.getJSONObject("data")
            // Only the hourly head of the series has next_1_hours; later steps are 6-hourly.
            val symbol = data.optJSONObject("next_1_hours")?.getJSONObject("summary")?.getString("symbol_code") ?: break
            val d = data.getJSONObject("instant").getJSONObject("details")
            val t = d.getDouble("air_temperature")
            // shortcut: device time zone, not the place's; upgrade if places in other zones matter.
            val time = Instant.parse(s.getString("time")).atZone(zone).format(fmt)
            val code = metToWmo(symbol)
            val isDay = if (symbol.endsWith("_night")) 0 else 1
            if (current == null) {
                today = time.substringBefore('T')
                current = JSONObject().put("time", time).put("temperature_2m", t)
                    .put("apparent_temperature", apparent(t, d.getDouble("relative_humidity"), d.getDouble("wind_speed")))
                    .put("weather_code", code).put("is_day", isDay)
            }
            // shortcut: MET has no past hours, so today's min/max covers only the rest of the day.
            if (time.startsWith(today)) { min = minOf(min, t); max = maxOf(max, t) }
            times.put(time); temps.put(t); codes.put(code); days.put(isDay)
        }
        if (current == null) throw IOException("empty MET forecast")
        return JSONObject()
            .put("current", current)
            .put("hourly", JSONObject().put("time", times).put("temperature_2m", temps).put("weather_code", codes).put("is_day", days))
            .put("daily", JSONObject().put("temperature_2m_max", JSONArray().put(max)).put("temperature_2m_min", JSONArray().put(min)))
            .toString()
    }

    // Steadman's apparent temperature, as Open-Meteo computes it (wind in m/s).
    private fun apparent(t: Double, rh: Double, wind: Double) =
        t + 0.33 * rh / 100 * 6.105 * exp(17.27 * t / (237.7 + t)) - 0.70 * wind - 4.0

    // MET symbol codes (api.met.no/weatherapi/weathericon/2.0) → the WMO codes our icons use; sleet counts as rain.
    private fun metToWmo(symbol: String) = when {
        "thunder" in symbol -> 95
        "snow" in symbol -> 71
        "fog" in symbol -> 45
        symbol.startsWith("clearsky") -> 0
        symbol.startsWith("fair") || symbol.startsWith("partlycloudy") -> 2
        symbol.startsWith("cloudy") -> 3
        else -> 61
    }

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
            c.setRequestProperty("User-Agent", "dev.rtxbb.weather/1.0") // MET rejects requests without one
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
