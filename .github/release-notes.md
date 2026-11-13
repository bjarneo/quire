Quire 0.5.3 adds pinned notes. A pinned note shows in the Pinned group at the top of the file tree.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.3-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.3-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.3-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.3-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## New

- **Pinned notes.** Pin a note to keep it at the top of the file tree, in the Pinned group. The group has a pin icon in place of the folder icon. The note leaves its folder in the tree, but the file stays where it is on disk, so links, sync and other apps see no change.
- On the desktop, pin a note in one of these ways:
  - Right-click it and select **Pin**.
  - Drag it onto the Pinned group.
  - Select the pin button in the formatting toolbar.
  - Use **Pin note** in the command palette.
- On Android, long-press a note and select **Pin**, or use the pin button in the Markdown toolbar.
- A pinned note from a folder shows the folder name next to it. The pin stays when you rename the note or move it to another folder. The toolbar pin uses the accent colour while the note is pinned.

Pins are saved for each notes folder on each device. They do not sync yet.
