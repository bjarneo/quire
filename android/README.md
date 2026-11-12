# Quire for Android

Quire is a plain editor for folders of Markdown notes. This is the Android app. It uses Kotlin and Jetpack Compose.

## Requirements

- JDK 17 or later. JDK 21 works.
- The Android SDK with platform `android-36` and build tools `36.0.0`.
- A `local.properties` file that points to the SDK, or the `ANDROID_HOME` variable.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

The Gradle wrapper downloads Gradle 9.9.1 on the first build.

## Build

To build the debug APK, run this command:

```sh
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Install

To install on a device over adb, run this command:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If more than one device is connected, give the serial number:

```sh
adb devices
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Where the notes are

- "Create a new folder" makes a folder in the app storage, under `files/vaults/<name>`.
- "Open a folder…" uses the system folder picker. Quire keeps the permission, so the folder opens again after a restart. Use it for a folder that another app syncs.
- On the first start, Quire writes the sample folder "Field Notes". Settings > Files & links > Restore puts it back.

To make a folder, use "New folder" in the drawer or in the command palette. Long-press a folder to add a note or a folder inside it. The "+" at the end of a folder row adds a note there.

To move a note, long-press it in the drawer and drag it onto a folder. Drop it on the folder name at the top to move it to the root. You can also long-press a note and pick "Move to…". In the quick switcher, type a path such as `Drafts/Idea` to create a note in that folder.

Quire saves an edit 500 ms after you stop typing, and again when the app goes to the background. It reads the folder again when the app comes back, so edits from other apps show up.

## Sync settings

The Sync & backup section stores the provider settings. Credentials and the passphrase are encrypted with a key in the Android Keystore. Quire does not sync yet. "Test connection" checks the required fields only.

## Fonts

The fonts are under the SIL Open Font License. The license files are in `app/src/main/assets/licenses`.
