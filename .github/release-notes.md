Quire 0.5.2 makes touchpad scrolling faster on Linux and turns the formatting toolbar into a floating bar that stays in view.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.2-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.2-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.2-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.2-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## Changed

- The formatting toolbar floats at the top of the note. It stays in view while the note scrolls under it, and the text fades out at the top. At rest it has no background, and its first icon lines up with the text. While the note scrolls, a soft background and shadow come in under the buttons.
- The caret stays below the toolbar while you type.

## Fixed

- On Wayland, a touchpad swipe now scrolls a note as far as Chromium scrolls a web page. Before, Quire moved the note 1 point for each point of the swipe, so it scrolled slowly. This was worse on Omarchy, which sets the touchpad `scroll_factor` to 0.4. Mouse wheel steps do not change.
- A click between the toolbar buttons no longer moves the caret to the end of the note.

The Android app has no changes in this release. Its version number goes up to match.
