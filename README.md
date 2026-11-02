# GameTabs

GameTabs runs several copies of one installed Android game and lets you switch between them with a floating tab bar.

## How it works

```
 ┌──────────── GameTabs (host app) ─────────────┐
 │ MainActivity      pick game + instance count │
 │ InstanceManager   clone / launch / switch    │
 │ TabOverlayService floating tabs + keep-alive │
 └──────────────┬───────────────────────────────┘
                │ BlackBox virtual engine (Bcore)
   ┌────────────┼─────────────┬──────────────┐
   ▼            ▼             ▼              ▼
 user 0       user 1        user 2   ...   user N
 game copy    game copy     game copy      (own process,
 own login    own login     own login       own data dir)
```

- **Isolation:** each tab is the game installed in its own BlackBox *virtual user*. Every copy has its own process, data folder and account, so logins and saves stay separate.
- **Switching:** tapping a tab calls `launchApk(pkg, userId)`. If that copy is already running, its existing task comes to the front without restarting or reloading. That's why switching feels instant.
- **Keep-alive:** a foreground service holds the host at high priority, which keeps Android's low-memory killer away from background copies.
- **Tab bar:** games run fullscreen, so the tabs are a small overlay window that floats on top and can be dragged.
  - Tap **⇆** to collapse or expand the bar. Drag it to move it.
  - **Tap** a tab to switch to it. **Long-press** a tab to close that copy.
  - Tab colours: green means on screen, blue-grey means alive in the background, dark means not started.
  - **⌂** goes back to setup.

## Install (no build needed)

Every push to `main` triggers GitHub Actions, which builds the APK and posts it under
**Releases → "GameTabs latest build"**.

1. On your phone, open that release and download **GameTabs-arm64.apk**. Only pick `GameTabs-arm32.apk` for old 32-bit games.
2. Allow "Install unknown apps" for your browser when Android asks.
3. Open GameTabs and allow **Display over other apps**.
4. Pick the game, set how many copies you want, and tap **Start**.

## Build it yourself

The BlackBox engine is included, from the maintained fork at `dreamncn/BlackBox` under the Apache-2.0 licence. The original FBlackBox repo was deleted.

You need JDK 11, Android SDK 32, NDK 23.1.7779620 and CMake 3.18.1. Then run:

```
./gradlew assembleArm64Release
```

The APK ends up in `app/build/outputs/apk/arm64/release/`.

## Performance: what's realistic

| Factor | Reality |
|---|---|
| Frame rate of the tab on screen | Close to native. The game runs as normal Android code, not in an emulator. The overlay doesn't cost frame time. |
| Switch time to a running tab | Usually under 300 ms, the same as switching apps in Recents. |
| Background tabs | Android pauses rendering for activities that aren't visible. Background copies stay logged in but usually don't keep "playing". Many games also cut their connection after a few minutes in the background. |
| RAM | Each copy needs the game's full RAM. A heavy 3D game needs about 1.5–3 GB per copy. With too many copies, Android kills the oldest one, and that tab cold-starts again. |
| Thermals | Several live copies drain the battery and heat the phone. |

Tips for lower latency:
- Use a phone with 12 GB of RAM or more.
- Exclude GameTabs from battery optimisation.
- On Xiaomi and Huawei phones, turn on "Autostart" and lock GameTabs in Recents.

## Known limits and risks

- **Anti-cheat and terms of service:** many online games detect virtual environments and multiple accounts. Some ban for it. Read the game's terms before using alt accounts.
- **Google Play Services:** games that log in through Google Play Games may fail inside virtual users.
- **targetSdk 28:** this keeps the engine compatible, but the app can't go on the Play Store. Sideload it instead.
- **64-bit only by default:** for old 32-bit games, change `abiFilters` to `armeabi-v7a`.
