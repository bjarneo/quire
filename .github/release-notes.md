Quire 0.3.0 adds themes. Pick one theme for light mode and one for dark mode, and pick your own accent colour.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.3.0-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.3.0-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.3.0-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.3.0-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

## Themes

- Settings, Appearance has 12 themes:
  - For light mode: Paper, Solarized Light, Gruvbox Light, Catppuccin Latte and Rosé Pine Dawn.
  - For dark mode: Ink, Nord, Gruvbox Dark, Catppuccin Mocha, Tokyo Night, Rosé Pine and Solarized Dark.
- Pick one theme for light mode and one for dark mode. **Match system** switches between them.
- **Accent colour** offers 9 swatches and a field for any `#rrggbb` value. An accent that is too dark to read on a dark theme gets lighter for text.
- On Omarchy, the current Omarchy theme is a choice in both lists.
- Paper and Ink stay the defaults, so Quire looks the same until you pick another theme.

The desktop app and the Android app have the same themes and accents.
