# Don't Oversleep · Не проспи · Уктап калба

**A stop alarm for Android that works worldwide.** Pick your stop on the map, start the trip and lock your phone.
The app follows your GPS in the background, gives you a gentle heads-up first, then a loud alarm over the lock screen.

**Будильник на остановку для Android, работает в любой стране.** Отмечаете остановку на карте, начинаете поездку и блокируете телефон — приложение разбудит вас перед выходом.

## 📥 Download / Скачать
**[Latest APK / Последняя версия](https://github.com/Emirkhan-Sharshenov/ne_prospi_app/releases/latest)** — open the file on your phone and allow installation. Android 8.0+.

## Features
**Works everywhere**
- 🌍 Any country: map, address search and stops (bus, tram, train, metro, ferry) from OpenStreetMap
- 🗺️ Offline maps for any country or region (mapsforge), the app suggests your country's map
- 📏 Kilometres or miles — chosen automatically by country
- 🗣️ English, Русский, Кыргызча
- 🇰🇬 Kyrgyzstan's 1,557 stops are built in and work without internet

**Reliability**
- 🛟 Backup timer — rings after a set time even if GPS is lost or Android closes the app
- ✋ Hold-to-dismiss, then “Are you awake?” one minute later; no answer — the alarm rings again
- ⚠️ Wakes you again if you passed your stop
- 🛡️ Reliability check with fixes and tips for Samsung, Xiaomi, Huawei, OPPO
- 🔋 Battery saving: GPS is polled less often while your stop is far away; GPS-lost and low-battery warnings

**Comfort**
- Full-screen map with a three-step bottom panel: where to → when to wake → trip progress
- Lock screen progress bar and arrival countdown, voice announcements
- Home screen widget, shortcuts, recent trips, history
- Vibration-only, headphones-only, flashlight, custom sound
- Share your trip with family via any messenger, or automatic SMS

## Project structure
- `MainActivity.kt` — wires the main screen parts together
- `main/MapController.kt` — map, tiles (online/offline), stops layer, destination, “me”
- `main/SearchPanel.kt` — search bar and results
- `main/SheetPanels.kt` — bottom panel: `IdlePanel`, `DestinationPanel`, `TripPanel`
- `main/TripLauncher.kt` — permission checks and starting a trip
- `main/PlaceActions.kt` — saved places, shortcuts, sharing, history
- `TripService.kt` — background trip: GPS, alarms, backup timer, voice, SMS
- `AlarmActivity.kt` — alarm screen over the lock screen
- `Stops.kt` — stops worldwide (built-in Kyrgyzstan + OpenStreetMap by area, cached for 30 days)
- `OfflineMap.kt` + `OfflineMapsActivity.kt` — offline maps catalogue and downloads
- `Net.kt` — address search (Nominatim); `Geo.kt` — distances and units; `Lang.kt` — language and country
- `SettingsActivity.kt`, `SetupActivity.kt` + `Reliability.kt`, `TripWidget.kt`, `Prefs.kt`

## Build
`build.bat` → `NeProspi.apk` (JDK 17, Android SDK 35)

## Limitations
- Stops, search and maps use free public OpenStreetMap services. They are fine for personal use and small groups; for thousands of users the app would need its own servers or a paid provider.
- Stop data depends on how well your city is mapped in OpenStreetMap. You can always tap any point on the map — the alarm works by GPS.
- The alarm can't work if the phone is switched off or in airplane mode.
- Translations (Kyrgyz and English) were not reviewed by native speakers — corrections are welcome.

Map and stop data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL). Offline maps: [mapsforge](https://download.mapsforge.org/).
