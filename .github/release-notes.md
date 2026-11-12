Quire 0.2.0 syncs your notes between devices through object storage that you control.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.2.0-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.2.0-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.2.0-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.2.0-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## Sync

- Connect a notes folder to Amazon S3, Hetzner, Azure Blob Storage, Google Cloud Storage, Cloudflare R2, Backblaze B2, DigitalOcean Spaces, Wasabi, Scaleway, OVHcloud or any S3-compatible server.
- **Test connection** makes a real request and tells you what to fix when it fails.
- Sync runs on save, every 5 minutes, or manually. **Sync now** is in the settings and the command palette.
- **Encrypt before upload** uses AES-256-GCM with a key from your passphrase. The provider stores neither note names nor note text.
- Folders sync too, empty ones included. A folder you delete on one device moves to `.trash` on the others.
- When two devices change the same note, Quire keeps both versions.
- The desktop and Android apps use the same format. `SYNC.md` describes it.

## Folders

- To delete a folder, right-click it and select **Delete folder**. A folder with notes asks first. The folder moves to `.trash` with every file in it.

## Focus mode

- A button in the title bar turns on focus mode. `Ctrl+.` still works too.

## Title bar

- On Linux, the title bar has no window buttons. Your window manager closes and places the window. To quit, press `Ctrl+Q` or use **Quit** in the command palette.

## Fixed

- The first tab lines up with the left edge of the note.
- Quit saves notes that you edited in the last second. Before, the last edit could be lost.

## Not in this release

- With "Ask before changes" on, MCP tools that write return an error, because there is no approval prompt yet.
