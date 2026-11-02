# GameTabs

GameTabs puts a browser-style tab bar on top of your game so you can jump between real copies of the game installed on your phone.

```
[● Game 1][● Game 2][+]                    [⌂][▴]
```

## How it works

- **Each tab is a real install of the game** in its own Android profile. Game 1 is your normal profile. Game 2 lives in a work profile that Shelter or Island creates for you.
- **Nothing is cloned or injected.** Android itself installs and runs every copy, so each one runs at full native speed.
- **Switching** uses the same system call your home screen uses. A copy that's already running comes straight back to the front without reloading.
- **Tab bar controls:**
  - **Tap** a tab to switch to that copy.
  - **+** shows how to add a copy.
  - **⌂** opens GameTabs.
  - **▴** hides the bar. Tap "▾ Tabs" to bring it back.

## Getting a second copy (one time)

1. Install **Shelter** from F-Droid, or **Island** from the Play Store.
2. Open it and let it create a work profile.
3. Clone your game into the work profile.
4. Open the work copy once and log in with your second account.
5. In GameTabs, tap **Rescan**. It should now say "2 copies found".

Android allows one work profile per phone, so 2 copies is the usual maximum.

## Install

Download **GameTabs.apk** from Releases → "GameTabs latest build". Every push to `main` rebuilds it.

## Build

You need JDK 11 and Android SDK 32. Then run:

```
./gradlew assembleRelease
```
