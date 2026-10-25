package dev.rtxbb.weather

import dev.rtxbb.weather.data.OpenMeteo
import dev.rtxbb.weather.data.weatherIcon
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class WeatherParseTest {
    private val json = """
        {"city":"Москва",
         "current":{"time":"2026-10-25T13:15","temperature_2m":7.6,"apparent_temperature":4.4,"weather_code":61,"is_day":1},
         "hourly":{"time":["2026-10-25T13:00","2026-10-25T14:00","2026-10-25T15:00"],
                   "temperature_2m":[7.0,8.4,6.5],"weather_code":[61,3,0],"is_day":[1,1,0]},
         "daily":{"temperature_2m_max":[9.2,10.0],"temperature_2m_min":[3.4,2.0]}}
    """

    @Test
    fun parsesCurrentAndUpcomingHours() {
        val w = OpenMeteo.parse(json)
        assertEquals("Москва", w.city)
        assertEquals(8, w.temp)
        assertEquals(4, w.feelsLike)
        assertEquals(3, w.min)
        assertEquals(9, w.max)
        // 13:00 is already past 13:15, so the strip starts at 14:00
        assertEquals(listOf("14:00", "15:00"), w.hourly.map { it.time })
        assertEquals(false, w.hourly[1].isDay)
    }

    private fun met(time: String, t: Double, symbol: String?) = """
        {"time":"$time","data":{"instant":{"details":{"air_temperature":$t,"relative_humidity":80.0,"wind_speed":3.0}}
         ${symbol?.let { ""","next_1_hours":{"summary":{"symbol_code":"$it"}}""" } ?: ""}}}
    """

    @Test
    fun convertsMetForecast() {
        val series = listOf(
            met("2026-10-25T10:00:00Z", 7.6, "lightrain"),
            met("2026-10-25T11:00:00Z", 9.4, "partlycloudy_day"),
            met("2026-10-25T21:00:00Z", 2.0, "clearsky_night"),
            met("2026-10-26T00:00:00Z", -9.0, null),
        ).joinToString(",")
        val w = OpenMeteo.parse(OpenMeteo.fromMet("""{"properties":{"timeseries":[$series]}}""", ZoneOffset.ofHours(3)))
        assertEquals(8, w.temp)
        assertEquals(4, w.feelsLike)
        assertEquals(61, w.code)
        // 21:00Z is already tomorrow at +3, and the 6-hourly step is dropped
        assertEquals(8, w.min)
        assertEquals(9, w.max)
        assertEquals(listOf("14:00", "00:00"), w.hourly.map { it.time })
        assertEquals(listOf(2, 0), w.hourly.map { it.code })
        assertEquals(false, w.hourly[1].isDay)
    }

    @Test
    fun mapsWmoCodes() {
        assertEquals(R.drawable.ic_wx_clear_night, weatherIcon(0, isDay = false))
        assertEquals(R.drawable.ic_wx_rain, weatherIcon(81, isDay = true))
        assertEquals(R.drawable.ic_wx_snow, weatherIcon(85, isDay = true))
        assertEquals(R.drawable.ic_wx_thunder, weatherIcon(95, isDay = true))
    }
}
