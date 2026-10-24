package dev.rtxbb.weather.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import android.text.format.DateFormat
import dev.rtxbb.weather.MainActivity
import dev.rtxbb.weather.R
import dev.rtxbb.weather.data.OpenMeteo
import dev.rtxbb.weather.data.Prefs
import dev.rtxbb.weather.data.Weather
import dev.rtxbb.weather.data.weatherIcon
import dev.rtxbb.weather.data.weatherLabel
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Forecast JSON lives in each widget's Glance state, so it doubles as the offline cache.
private val WEATHER = stringPreferencesKey("weather")

private suspend fun loadWeather(ctx: Context): String? {
    val p = Prefs.load(ctx) ?: return null
    return runCatching { JSONObject(OpenMeteo.fetchJson(p.lat, p.lon)).put("city", p.name).toString() }
        .onFailure { Log.w("Weather", "forecast fetch failed", it) }
        .getOrNull()
}

/** Called by the app after the place changes; also updates widget sessions that are already running. */
suspend fun refreshWidgets(ctx: Context) {
    val json = loadWeather(ctx) ?: return
    val manager = GlanceAppWidgetManager(ctx)
    for (widget in listOf(CurrentWeatherWidget(), ForecastWidget())) {
        manager.getGlanceIds(widget.javaClass).forEach { id ->
            updateAppWidgetState(ctx, id) { it[WEATHER] = json }
            widget.update(ctx, id)
        }
    }
}

abstract class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // The session loads state once at start and re-reads it only on update(), so write-then-update
            // here too; until then the cached forecast is shown.
            LaunchedEffect(Unit) {
                loadWeather(context)?.let { json ->
                    updateAppWidgetState(context, id) { it[WEATHER] = json }
                    update(context, id)
                }
            }
            GlanceTheme {
                val w = currentState(WEATHER)?.let { runCatching { OpenMeteo.parse(it) }.getOrNull() }
                Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>())) {
                    if (w == null) {
                        Text(
                            LocalContext.current.getString(R.string.widget_no_data),
                            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(16.dp),
                            style = TextStyle(color = GlanceTheme.colors.onSurface),
                        )
                    } else {
                        Content(w)
                    }
                }
            }
        }
    }

    @Composable
    abstract fun Content(w: Weather)
}

private fun temp(t: Int) = "$t°"

/** 2×2: big temperature over a tilted pill, large condition icon bottom-start (Pixel's "Current weather"). */
class CurrentWeatherWidget : WeatherWidget() {
    @Composable
    override fun Content(w: Weather) {
        Box(GlanceModifier.fillMaxSize()) {
            Image(
                ImageProvider(R.drawable.widget_pill), null, GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground),
            )
            // Fit keeps the pill centered whatever the cell aspect, so both items are offset from the center
            // (asymmetric padding in a centered Box shifts the child by half of it).
            Box(GlanceModifier.fillMaxSize().padding(start = 12.dp, bottom = 36.dp), contentAlignment = Alignment.Center) {
                Text(temp(w.temp), style = TextStyle(fontSize = 64.sp, color = GlanceTheme.colors.onSurface))
            }
            Box(GlanceModifier.fillMaxSize().padding(end = 76.dp, top = 84.dp), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(weatherIcon(w.code, w.isDay)),
                    LocalContext.current.getString(weatherLabel(w.code)),
                    GlanceModifier.size(58.dp),
                )
            }
        }
    }
}

/** City, condition, feels like, max · min, big temperature and an hourly strip (Pixel's "Forecasted weather"). */
class ForecastWidget : WeatherWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, FULL))

    @Composable
    override fun Content(w: Weather) {
        val ctx = LocalContext.current
        val onSurface = GlanceTheme.colors.onSurface
        val full = LocalSize.current.width >= FULL.width && LocalSize.current.height >= FULL.height
        val big = TextStyle(fontSize = 19.sp, color = onSurface)
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(16.dp),
        ) {
            Text(w.city, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(weatherIcon(w.code, w.isDay)), null, GlanceModifier.size(26.dp))
                        Spacer(GlanceModifier.width(8.dp))
                        Text(ctx.getString(weatherLabel(w.code)), style = big, maxLines = 1)
                    }
                    Text(ctx.getString(R.string.feels_like, temp(w.feelsLike)), style = big, maxLines = 1)
                    Text("${temp(w.max)} · ${temp(w.min)}", style = big)
                }
                Text(temp(w.temp), style = TextStyle(fontSize = if (full) 64.sp else 40.sp, color = onSurface))
            }
            if (full) {
                Spacer(GlanceModifier.defaultWeight())
                // Same 24h/12h choice as the system clock: "14:00" or "2 PM".
                val fmt = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h a")
                Row(
                    GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.background).cornerRadius(20.dp).padding(vertical = 10.dp),
                ) {
                    HourCell(temp(w.temp), weatherIcon(w.code, w.isDay), ctx.getString(R.string.now))
                    w.hourly.forEach { h -> HourCell(temp(h.temp), weatherIcon(h.code, h.isDay), LocalTime.parse(h.time).format(fmt)) }
                }
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.HourCell(t: String, icon: Int, time: String) {
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
            Image(ImageProvider(icon), null, GlanceModifier.size(26.dp).padding(vertical = 3.dp))
            Text(time, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
        }
    }

    companion object {
        private val SMALL = DpSize(109.dp, 115.dp)
        private val FULL = DpSize(250.dp, 200.dp)
    }
}

class CurrentWeatherReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = CurrentWeatherWidget()
}

class ForecastReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ForecastWidget()
}
