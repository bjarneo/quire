Quire 0.5.9 shows Markdown tables as tables.

## Download

| Platform | File | How to start it |
| --- | --- | --- |
| Linux, x86-64 | `quire-0.5.9-linux-x86_64.tar.gz` | Unpack it and run `./install.sh`. Then open Quire from the app launcher, or run `quire`. |
| macOS, Apple silicon | `quire-0.5.9-macos-arm64.zip` | Unzip it and move `Quire.app` to Applications. The app is not notarized, so right-click it and choose **Open** the first time. |
| Windows, x86-64 | `quire-0.5.9-windows-x86_64.zip` | Unzip it and run `quire.exe`. The app is not signed, so Windows SmartScreen can warn you. Choose **More info**, then **Run anyway**. |
| Android 8.0 and later | `quire-0.5.9-android.apk` | Open the file on your phone and allow the install. |

The Linux build needs Wayland or X11 with Vulkan. The macOS and Windows builds come from CI and are not tested on those systems yet. Please open an issue if they do not start.

With 0.5.4 or later, select **Update** at the top right to install this version.

## New

- **Tables.** The preview shows a GitHub Markdown table as a table, on the desktop and on Android.

  ```markdown
  | Fruit  | Qty | Price |
  | :----- | :-: | ----: |
  | Apples |  3  |  1.20 |
  ```

  - The colons in the `---` row align a column left, center or right.
  - Bold, links, note links, tags and code work in the cells. Write `\|` for a pipe in a cell.
  - The columns share the width of the page, and long text wraps. A table that is too wide scrolls sideways, and a fade shows the hidden side.
  - Source mode shows the pipes and the `---` row in a light colour, and the header in bold.
  - A table can start right under a paragraph, as on GitHub.
