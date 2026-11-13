Quire 0.5.5 fixes line breaks in the preview and moves the desktop toolbar to the bottom of the note.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.5-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.5-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.5-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.5-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

With 0.5.4 or later, select **Update** at the top right to install this version.

## Changed

- On the desktop, the formatting toolbar floats at the bottom of the note, in the middle. The note scrolls under it, and the text fades out at the bottom. The caret stays above the toolbar while you type. A click beside the toolbar reaches the text under it.

## Fixed

- The preview keeps each line break as you typed it. Before, the lines of a paragraph or a quote joined into one line.
- A `\` or two spaces at the end of a line no longer show in the preview. The line breaks there.
- A line under a list item stays in that item. Before, it dropped out of the list as its own paragraph.
- The Android preview has the same fixes.
