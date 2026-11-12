Quire 0.4.0 adds images to notes, a formatting toolbar, more reliable sync, and two new themes: White and Black.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.4.0-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.4.0-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.4.0-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.4.0-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## Images

- The preview shows images: `![alt](path)` relative to the note, `![[name.png]]` anywhere in the folder, and `https://` images. PNG, JPEG, GIF, WebP, BMP, SVG and AVIF work.
- To add an image, drag it onto a note, paste it, or use the image button in the title bar (`Ctrl+Shift+I`). Quire copies it into `attachments/` and links it at the caret.
- On Android, pick an image from the photo picker, or drop one from another app.
- Sync carries the images in the notes folder, up to 50 MB each, with the same encryption and conflict rules as notes.
- File drops now work on Wayland too.

## Formatting toolbar

- A toolbar above the note in source mode makes text bold, italic or struck through, and adds headings, links, note links, lists, tasks, quotes, code and images.
- `Ctrl+B`, `Ctrl+I` and `Ctrl+K` do the same as the first buttons. On Android, the toolbar shows above the keyboard.
- To hide it on the desktop, turn off Settings, Editor, **Formatting toolbar**.

## Sync

- **Each notes folder has its own remote folder now.** Before, every notes folder on a device used the one **Remote folder** value, so two notes folders could mix their notes. Without a value, a folder syncs to `quire/<folder name>/`. If you set a custom remote folder before, set it again in Settings, Sync & backup for the folder that uses it.
- A request that the storage service does not answer in time is tried again up to 4 times. Before, one 504 from the service failed the whole sync.
- Downloads and uploads run 8 at a time, so a first sync of many notes is faster.
- **Test connection** says when the remote folder holds notes that this folder does not have.
- The desktop app sends its real host name as the device name in conflict copies.

## Themes

- **White** is a light theme with a pure white page.
- **Black** is a dark theme with a pure black page, for OLED screens.
