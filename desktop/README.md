# Quire for the desktop

Quire is a plain editor for folders of Markdown files. Each note is a `.md` file on your disk, so any other app can open it. This is the desktop app, built with [Rune](https://github.com/anomalyco/rune), a GPU-rendered Rust UI framework.

## Build and run

You need the Rust toolchain. `rust-toolchain.toml` pins the version that Rune uses, and `rustup` installs it on the first build. On Linux you also need the `libxkbcommon` and `wayland` development packages.

```sh
cargo run --release              # opens the last folder, or the welcome screen
cargo run --release -- ~/notes   # opens a folder
cargo run --release -- note.md   # opens a note in its folder
```

To install Quire for your user, with a launcher entry and an icon, run:

```sh
./install.sh
```

The script puts the binary in `~/.local/bin/quire`, the icon in `~/.local/share/icons` and `quire.desktop` in `~/.local/share/applications`.

## What it does

- Edits the `.md` files of a folder. Saves each note half a second after you stop typing, and on quit.
- Shows source with live Markdown highlighting, or a rendered preview (`Ctrl+E`).
- Has a formatting toolbar above the note in source mode: bold, italic, strikethrough, heading, link, note link, lists, task, quote, code and image. `Ctrl+B`, `Ctrl+I` and `Ctrl+K` do the same as the first buttons. Settings, Editor, **Formatting toolbar** hides it.
- Shows images in the preview: `![alt](path)` relative to the note, `![[name.png]]` anywhere in the folder, and `https://` images.
- Adds an image when you drop it on a note, paste it, or pick it with the image button (`Ctrl+Shift+I`). Images from outside the folder are copied into `attachments/`. On Wayland, file drops come from a small addition to Rune's winit in `vendor/winit`.
- Links notes with `[[Note name]]`. `Ctrl`-click a link to follow it. A link to a missing note creates the note.
- Finds tags (`#tag`), searches every note (`Ctrl+Shift+F`), and lists headings and backlinks.
- Opens any note with the quick switcher (`Ctrl+O`) and runs any command from the palette (`Ctrl+P`).
- Hides everything but the text in focus mode (`Ctrl+.`).
- Rewrites links in other notes when you rename a note.
- Makes folders: use the folder button next to the panel tabs, "New folder" in the command palette, or right-click a folder.
- Creates a note inside a folder: use the `+` on the folder's row, right-click the folder, or type `Folder/Name` in the quick switcher. A path creates the folders it names.
- Moves a note into a folder when you drag it onto the folder. Drop it on the folder name or on empty space to move it to the root. Links that spell out the old folder follow the note.
- Moves deleted notes to `.trash` inside the folder.
- Deletes a folder from its right-click menu. A folder with notes asks first. The folder moves to `.trash` with every file in it, so you can get it back.
- Picks up changes that other apps make to the folder while Quire is open.
- On macOS the shortcuts use `⌘` in place of `Ctrl`.
- Has 14 themes in Settings, Appearance: 6 for light mode and 8 for dark mode. You pick one for each mode, and "Match system" switches between them.
- Lets you pick the accent colour from 9 swatches, or type a `#rrggbb` value. An accent that is too dark to read on a dark theme gets lighter for text.
- On Omarchy, offers the current Omarchy theme in both lists and follows theme switches while Quire runs. Without a pick, "Match system" uses the Omarchy theme.

On first launch Quire writes a sample folder, Field Notes, to `~/Documents/Field Notes`. If that folder already exists, the sample goes to Quire's data folder instead. Settings and open tabs live in `~/.config/quire/state.json`, never in a notes folder.

## MCP server

Quire can serve a folder to AI apps that speak the Model Context Protocol, such as Claude Desktop or Cursor. Turn it on in Settings, MCP. Settings, MCP also shows the client configuration to copy.

With the stdio transport, the AI app starts Quire's helper itself:

```json
{ "mcpServers": { "quire": { "command": "quire", "args": ["mcp", "--folder", "/path/to/notes"] } } }
```

With the HTTP transport, Quire serves `http://127.0.0.1:3917/mcp` while the app is open. Each request needs the access token from Settings as a `Bearer` header.

Access is read-only by default. Each tool has its own switch, and hidden folders stay out of listings and search. When "Ask before changes" is on, tools that write return an error, because this version cannot show the approval prompt yet. Turn the option off to allow edits.

## Sync

Settings, Sync & backup connects a notes folder to a bucket. **Test connection** reads the remote folder and reports what it holds. After it passes, Quire syncs when the folder opens, after you save, every 5 minutes, or only when you select **Sync now**, as you choose. The status bar shows the last sync. Select it to open the sync settings.

Each notes folder has its own **Remote folder**. Without a value, it is `quire/<folder name>/`. A request that the service does not answer in time is tried again up to 4 times, and transfers run 8 at a time.

Quire keeps a record of the last sync for each folder and bucket in `~/.config/quire/sync/`. The record lets Quire tell an edit from a delete. See [`../SYNC.md`](../SYNC.md) for the format.

## Not in this version

- Very long notes type slower. A keystroke takes about 2.5 ms at 400 lines and 6 ms at 1,000 lines, but about 30 ms at 3,000 lines, because the text field lays out the whole note on each edit.

- The passphrase and keys you enter are saved in `~/.config/quire/state.json`, which only your user can read. They are not in the system keychain.

## Develop

```sh
cargo test                                         # unit tests
cargo run -- --snap shot.png --size 1280x800 --scale 2   # render a frame headless
cargo run -- --snap shot.png --scene dark          # scenes: welcome, preview, dark, focus, settings
```

`QUIRE_SMOOTHING` picks another text rasterizer, for comparing renders. `ref/` holds renders of the design in Chromium, used as the reference for pixel comparisons.
