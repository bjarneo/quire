Quire 0.5.8 adds a dictation button to the toolbar.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.8-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.8-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.8-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.8-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

With 0.5.4 or later, select **Update** at the top right to install this version.

## New

- **Dictate.** A microphone button in the toolbar starts the dictation of your system. The words go in at the caret.
  - macOS uses its own dictation. If dictation is off, turn it on in System Settings, Keyboard.
  - Omarchy runs `omarchy-dictation toggle`, with the backend from Setup, Defaults, Dictation. Select the button again to stop.
  - Android opens the voice input of the phone. A space goes before the words when they follow a word.
  - Windows and other Linux systems show no button.
