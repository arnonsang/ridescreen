Personal fork of **RideScreen AA**, set up for a **Zontes 368K**: wireless Android Auto on the bike's dash, plus the app's own ride screens.

Built on [OpenCfMoto](https://github.com/zanderp/open-cfmoto) by Alexandru (zanderp) and [RideScreen AA](https://github.com/mgb1982/ridescreen-aa) by Manel (mgb1982). Almost all of the app is their work. Not an official Zontes, CFMoto or Google app. AGPL-3.0.

> ⚠️ **Not tested on a bike yet.** Everything below compiles, its unit tests pass and the ride screens were checked by rendering them off-device, but none of it has run on a real dash. If something misbehaves, send the log from Help → Share logs.

### New in v3.1

- **Speed alert:** the speed turns red when you are over the limit by a margin you set (5 to 20 km/h, or off), with an optional beep. Limits come from OpenStreetMap, so treat it as a hint.
- **Share a place from Google Maps** and choose "Ride there (RideScreen)", or open a `geo:` link: it becomes the destination on the built-in map.
- **Four more layouts:** Classic (needle speedometer), Commute (rain timeline), Trip computer and Night. Twelve in all.
- **Your own accent colour**, and a choice of what to show: weather, bike battery, fuel range, now playing, speed limit.
- **Auto theme:** Daylight by day and your theme after sunset; optional dimming after sunset.
- **Mirror** joins Android Auto and Ride screen in the Dash screen switch.
- **Climb and descent** for the trip, on the Trip computer layout.

### Since v3

**Ride screens on the dash**
- Instrument layouts to show instead of Android Auto: Cruise, Street, Ride hub (with a live map), Tourer, Sport, Music, Race bar and Twin dials.
- Five colour themes: Gold 368K, Racing red, Neon night, Sunset and Daylight.
- A **Dash screen** page on the phone with a preview, the display switch, and the layout and theme pickers.
- Handlebar: left and right change the layout, Select changes the theme, Home shows the full map, Back returns. The voice button opens the phone's assistant.
- Shows GPS speed, trip, heading, altitude, clock, next turn and speed limit, temperature and rain timing, street name, fuel range from the refuel log, bike battery, sunrise and sunset, and what the phone is playing.

**Connection**
- Android Auto is told when a session ends, so the phone is no longer left on "Connecting to Android Auto…" after Stop or when the bike switches off.
- Auto-disconnect 45 seconds after the bike goes away while you are not riding (switch on the Dash screen page).
- Connection steps on the home screen and on the watch; tapping the watch's status line offers Stop.

**Look**
- Home screen redesign: carbon black, white and gold, a headline that follows the connection state, and a bottom nav.

**Under the hood**
- The in-app updater only looks at this repository's releases.
- No telemetry or live-location server is configured in these builds, so they send nothing.

### Install
- Phone: `RideScreen-AA-v3.1.apk`. Watch (optional): `RideScreen-AA-v3.1-WearOS-reloj.apk`, installed on the watch.
- If it refuses to install over an earlier build (v3 included), uninstall that one first: releases only share a signing key once the `DEBUG_KEYSTORE_B64` repository secret is set.
- First-time setup (Android Auto's head unit server, the dash QR) is described in the [RideScreen AA guide](https://ridescreen.app/guia/).
