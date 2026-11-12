Quire 0.5.0 locks single notes with their own password.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.0-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.0-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.0-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.0-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## Locked notes

- To lock a note, select the lock button at the end of the toolbar, or "Lock note with a password…" in the command palette. Enter a password of at least 8 characters, two times.
- Quire encrypts the note with AES-256-GCM and a key from the password. The file on disk, search, the link index and sync see only the encrypted text.
- A locked note asks for its password. After you enter it, you can read and edit the note, and each save encrypts it again.
- The note locks again when you close its tab or select the lock button.
- "Remove password" in the command palette saves the note as plain text again.
- If you forget the password, nobody can open the note.
- The desktop app and the Android app open the same locked notes. `SYNC.md` describes the format.

## Docs

- The READMEs, `SYNC.md` and the website describe all features of 0.4.0 and 0.5.0, with new screenshots and a new video.
