# LTS Weather — контекст

Порт виджетов Google Pixel Weather (`temp/Google+Pixel+Weather+…apks`, minSdk 35) в своё приложение `dev.rtxbb.weather` под **minSdk 33** (Android 13).
Код Google не переносился (закрытый API через GMS, проприетарные ресурсы) — виджеты пересобраны на Jetpack Glance по раскладке оригинала.

## Решения
- **Данные:** Open-Meteo, без ключа. Прогноз — `api.open-meteo.com`, поиск города — `geocoding-api.open-meteo.com`. Сеть — `HttpURLConnection` + `org.json`, без Retrofit.
- **Место:** GPS (только в foreground, `ACCESS_COARSE_LOCATION`) или поиск города. Виджеты берут сохранённое место → фоновая геолокация не нужна.
- **Единицы:** только °C. Иконки — свои векторы (Material Icons, Apache 2.0), не из APK.
- Размеры/ресайз/`updatePeriodMillis=2700000` виджетов взяты из `appwidget-provider` оригинала.

## Структура (`app/src/main/java/dev/rtxbb/weather/`)
- `data/Weather.kt` — модели, `OpenMeteo.fetchJson/fromMet/searchCity/parse`, WMO-код → иконка/строка.
- `data/Location.kt` — `Prefs` (SharedPreferences `places`: `selected` — место виджетов, `gps`, `saved` — список), `currentPlace()` (LocationManager + Geocoder).
- `widget/WeatherWidgets.kt` — `CurrentWeatherWidget` (2×2: наклонная пилюля, температура, иконка), `ForecastWidget` (4×3: город, состояние, «ощущается как», max·min, большая температура, полоса «Сейчас» + 6 часов), ресиверы, `refreshWidgets()`.
- `MainActivity.kt` — экран как у Pixel Weather: «Текущее местоположение», «Сохранённые места», FAB поиска. Тап по карточке = место для виджетов. При открытии вызывает `refreshWidgets()`.
- Ресурсы: `res/xml/widget_*_info.xml`, `res/layout/widget_*_preview.xml`, `res/drawable/ic_wx_*`, `widget_pill.xml`, строки en + ru.

## Важные детали Glance
- Прогноз (JSON + поле `city`) хранится в Glance-состоянии виджета (ключ `weather`) — это и офлайн-кэш.
- Сессия Glance читает состояние один раз при старте; после `updateAppWidgetState` обязательно `update(context, id)`. Поэтому загрузка идёт в `LaunchedEffect` внутри `provideContent` с write → update.
- Ошибки загрузки пишутся в logcat с тегом `Weather`.

## Текущий статус (2026-10-25)
- Сборка и unit-тесты (`WeatherParseTest`) проходят. На эмуляторе API 37 оба виджета и экран работают.
- **Открытая проблема:** на Realme Pad (Android 13) виджеты пишут «Откройте приложение…», карточки без погоды.
  Причина — сеть: `api.open-meteo.com` (157.90.83.168, Hetzner) недоступен у провайдера (`SocketTimeoutException`).
  С планшета работают: `geocoding-api.open-meteo.com`, `api.met.no`, `wttr.in`. ПК ходит через VPN (fake-IP 198.18.x.x), поэтому там всё работало.
- **Решение:** Open-Meteo с откатом на MET Norway. `OpenMeteo.fetchJson` при `IOException` берёт `api.met.no` (User-Agent обязателен) и `fromMet` переводит ответ в формат Open-Meteo, так что кэш и `parse` не менялись. min/max берутся из оставшихся часов сегодня, «ощущается как» — формула Стедмана, время — в поясе устройства. На планшете ещё не проверено.

## Проверка
```
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> logcat | grep -A5 "forecast fetch failed"
```
Вне рамок: удаление сохранённых мест, единицы °F, уведомления, радар, профиль.
