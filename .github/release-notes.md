The first release of Quire, a plain editor for folders of Markdown files. Each note is a `.md` file on your disk, so any other app can open it.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.1.0-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.1.0-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.1.0-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.1.0-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## What you get

- Source mode with live Markdown highlighting, and a preview that reads like a printed page.
- `[[Wiki links]]` that create missing notes, `#tags`, full-text search, an outline and backlinks.
- A quick switcher, a command palette and focus mode.
- Folders: make them, create a note inside one, and drag notes into them.
- Light and dark themes, three editor fonts, and Omarchy theme colours on Omarchy.
- An MCP server, so AI apps such as Claude Desktop and Cursor can read and search your notes.

## Not in this release

- Sync & backup has a settings form, but Quire does not upload anything yet.
- With "Ask before changes" on, MCP tools that write return an error, because there is no approval prompt yet.
