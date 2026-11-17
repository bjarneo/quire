Quire 0.5.7 stops the loss of typed words during a save or a sync, and keeps numbered lists in order.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.7-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.7-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.7-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.7-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

With 0.5.4 or later, select **Update** at the top right to install this version.

## Fixed

- **Typed words no longer go away.** Quire watches the notes folder for changes from other apps, and it also saw its own saves. Sometimes it read a save that was one step old and put that older text in the editor. The words typed after that save went away. This happened most often during a sync. Now Quire knows its own saves, and unsaved typing always wins.
- On Android, the same kind of loss could happen when the app came back from the background. A note you edit now keeps its text.
- **Numbered lists stay in order.** When you press Enter in a numbered list, Quire numbers the whole list again from its first number. An item that you add in the middle moves the items under it down. One undo takes the new item and the old numbers back.
- The preview counts a numbered list on from its first number, the way GitHub does. A list typed as `1. 1. 1.` shows as 1, 2 and 3.
- These fixes are on the desktop and on Android.
