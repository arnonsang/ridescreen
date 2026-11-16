Personal fork of **RideScreen AA**, set up for a **Zontes 368K**: wireless Android Auto on the bike's dash, plus the app's own ride screens.

Built on [OpenCfMoto](https://github.com/zanderp/open-cfmoto) by Alexandru (zanderp) and [RideScreen AA](https://github.com/mgb1982/ridescreen-aa) by Manel (mgb1982). Almost all of the app is their work. Not an official Zontes, CFMoto or Google app. AGPL-3.0.

> ⚠️ **Not tested on a bike yet.** Everything below compiles, its unit tests pass and the ride screens were checked by rendering them off-device, but none of it has run on a real dash. If something misbehaves, send the log from Help → Share logs.

### New in v3

**Ride screens on the dash**
- Eight instrument layouts to show instead of Android Auto: Cruise, Street, Ride hub (with a live map), Tourer, Sport, Music, Race bar and Twin dials.
- Five colour themes: Gold 368K, Racing red, Neon night, Sunset and Daylight.
- A **Dash screen** page on the phone with a preview, the Android Auto / Ride screen switch, and the layout and theme pickers.
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
- Phone: `RideScreen-AA-v3.apk`. Watch (optional): `RideScreen-AA-v3-WearOS-reloj.apk`, installed on the watch.
- Coming from another build, uninstall it first: this release is signed with this repository's own key.
- First-time setup (Android Auto's head unit server, the dash QR) is described in the [RideScreen AA guide](https://ridescreen.app/guia/).
