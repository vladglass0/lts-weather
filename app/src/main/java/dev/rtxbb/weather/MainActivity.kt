package dev.rtxbb.weather

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rtxbb.weather.data.OpenMeteo
import dev.rtxbb.weather.data.Place
import dev.rtxbb.weather.data.Prefs
import dev.rtxbb.weather.data.currentPlace
import dev.rtxbb.weather.data.weatherIcon
import dev.rtxbb.weather.data.weatherLabel
import dev.rtxbb.weather.ui.theme.LTSWeatherTheme
import dev.rtxbb.weather.widget.refreshWidgets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LTSWeatherTheme {
                WeatherScreen()
            }
        }
    }
}

/** Layout of Pixel Weather's home: current location, saved locations, search FAB. Tapping a card picks the widget place. */
@SuppressLint("MissingPermission") // currentPlace() is only called from the permission-granted callback
@Composable
fun WeatherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(Prefs.load(ctx)) }
    var gps by remember { mutableStateOf(Prefs.gps(ctx)) }
    var saved by remember { mutableStateOf(Prefs.saved(ctx)) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun select(p: Place) {
        Prefs.save(ctx, p)
        selected = p
        scope.launch { refreshWidgets(ctx) }
    }

    val locate = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = ctx.getString(R.string.location_failed)
        else scope.launch {
            val p = currentPlace(ctx) ?: return@launch run { error = ctx.getString(R.string.location_failed) }
            error = null
            Prefs.saveGps(ctx, p)
            gps = p
            select(p)
        }
    }

    LaunchedEffect(Unit) { refreshWidgets(ctx) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { searching = true }, containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search_city))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp)) }
            item { SectionHeader(R.drawable.ic_my_location, R.string.current_location) }
            item {
                val g = gps
                if (g == null) {
                    PillCard(selected = false, onClick = { locate.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Text(stringResource(R.string.my_location), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    PlaceCard(g, g == selected) { locate.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { SectionHeader(R.drawable.ic_pin, R.string.saved_locations) }
            if (saved.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.search_to_save), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(saved) { p -> PlaceCard(p, p == selected) { select(p) } }
        }
    }

    if (searching) SearchDialog(onDismiss = { searching = false }) { p ->
        searching = false
        Prefs.addSaved(ctx, p)
        saved = Prefs.saved(ctx)
        select(p)
    }
}

@Composable
private fun SectionHeader(icon: Int, title: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PillCard(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().height(88.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun PlaceCard(place: Place, selected: Boolean, onClick: () -> Unit) {
    val weather by produceState(initialValue = null as dev.rtxbb.weather.data.Weather?, place) {
        value = runCatching { OpenMeteo.parse(OpenMeteo.fetchJson(place.lat, place.lon)) }.getOrNull()
    }
    PillCard(selected, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            weather?.let { Image(painterResource(weatherIcon(it.code, it.isDay)), null, Modifier.size(56.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                weather?.let { Text("${stringResource(weatherLabel(it.code))} · ${it.max}° / ${it.min}°", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            weather?.let { Text("${it.temp}°", style = MaterialTheme.typography.displaySmall) }
        }
    }
}

@Composable
private fun SearchDialog(onDismiss: () -> Unit, onFound: (Place) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_city)) },
        text = {
            OutlinedTextField(query, { query = it }, singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } })
        },
        confirmButton = {
            TextButton(enabled = query.isNotBlank(), onClick = {
                scope.launch {
                    runCatching { OpenMeteo.searchCity(query) }
                        .onSuccess { p -> if (p == null) error = ctx.getString(R.string.city_not_found) else onFound(p) }
                        .onFailure { error = ctx.getString(R.string.network_error) }
                }
            }) { Text(stringResource(R.string.find)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
