# Quire website

This folder is the promotional site for Quire. It is plain HTML, CSS and JavaScript with no build step. GitHub Pages serves it as it is.

## Files

| Path | Content |
| --- | --- |
| `index.html` | The page. |
| `styles.css` | All styles. Colours and type follow `design/Quire.dc.html`. |
| `site.js` | Repository links, the theme switch, the screenshot switches and the copy button. |
| `favicon.svg` | The app icon. It is `icon-paper.svg` in a light theme and `icon-ink.svg` in a dark theme. A media query inside the SVG switches them. |
| `favicon-32.png` | A 32 x 32 PNG of `icon-paper.svg`, for browsers without SVG icons. |
| `apple-touch-icon.png` | A 180 x 180 PNG of `icon-paper.svg` on white, for the iOS home screen. |
| `fonts/` | WOFF2 fonts and their SIL Open Font License files. |
| `assets/` | Screenshots and the social image. |
| `.nojekyll` | Stops Jekyll from processing the files on a branch deploy. |

## Preview locally

Run this command from the repository root:

```sh
python3 -m http.server -d site 8000
```

Then open <http://localhost:8000>. You can also open `site/index.html` directly in a browser.

## Set the repository URL

The repository URL is in one place: the `data-repo` attribute on the `<html>` element in `index.html`.

```html
<html lang="en" data-repo="https://github.com/bjarneo/quire">
```

The page points to `https://github.com/bjarneo/quire`. To move it, change that value. `site.js` builds every source, release and issue link from this value. The download buttons point to `<repo>/releases/latest`.

Social previews need absolute URLs, and a script cannot set them for crawlers. The `canonical`, `og:url`, `og:image` and `twitter:image` tags use the placeholder site URL `https://OWNER.github.io/quire/`.

The Pages workflow replaces both placeholders at deploy time. If you deploy another way, replace them yourself. This example uses `you` as the account name:

```sh
sed -i \
  -e 's#https://github.com/OWNER/quire#https://github.com/you/quire#g' \
  -e 's#https://OWNER.github.io/quire#https://you.github.io/quire#g' \
  site/index.html
```

## Deploy with GitHub Pages

### With the workflow

The workflow is `.github/workflows/pages.yml`. It uses `actions/upload-pages-artifact` and `actions/deploy-pages` to publish the `site/` folder.

1. Push the repository to GitHub.
2. In the repository, open **Settings > Pages**.
3. Set **Source** to **GitHub Actions**.
4. Push a change under `site/` to `main`, or run the workflow from the **Actions** tab.

The workflow runs when files in `site/` or the workflow file change on `main`. If the default branch has another name, change `branches` in the workflow.

### With a gh-pages branch

1. Replace the two placeholders as shown above, and commit the change.
2. Push the `site/` folder as the root of the `gh-pages` branch:

   ```sh
   git subtree push --prefix site origin gh-pages
   ```

3. In **Settings > Pages**, set **Source** to **Deploy from a branch**.
4. Select the `gh-pages` branch and the `/ (root)` folder.

## Screenshots

The current images are reference renders from `desktop/ref/`. To use screenshots of the real app, save each new image over the file with the same name.

Keep the desktop screenshots at 1280 x 800. If you use another size, keep the 16:10 ratio, or change the `width` and `height` attributes of that image in `index.html`.

| File | What it shows | Current source |
| --- | --- | --- |
| `assets/editor-light.png` | The main window in the light theme. A note is open in source mode. The file tree is on the left. The outline and the "linked from" list are on the right. The hero and the "source" view of the editing section use it. | `desktop/ref/vault.png` |
| `assets/editor-dark.png` | The same view in the dark theme. | `desktop/ref/dark.png` |
| `assets/preview-light.png` | The same note in preview mode, light theme. | `desktop/ref/preview.png` |
| `assets/preview-dark.png` | The same note in preview mode, dark theme. | `desktop/ref/dark-preview.png` |
| `assets/tags.png` | The tags panel in the sidebar, with the outline and "linked from" on the right. Light theme. | `desktop/ref/tags.png` |
| `assets/search.png` | The search panel with a query and its results. Light theme. | `desktop/ref/search.png` |
| `assets/quick-switcher.png` | The quick switcher open on `Ctrl+O`, with a short query and fuzzy matches. Light theme. | `desktop/ref/switcher-query.png` |
| `assets/command-palette.png` | The command palette open on `Ctrl+P`. Light theme. | `desktop/ref/commands.png` |
| `assets/focus-mode.png` | Focus mode in the light theme. The page fades the edges of this image into the paper colour `#f8f5ef`, so keep the light theme. The page crops part of the empty space above the text. | `desktop/ref/focus.png` |
| `assets/settings-appearance.png` | **Settings > Appearance**: the mode switch and the light theme cards, with Paper selected. | A headless render at `--scale 2`, resized to 1280x800 with `magick <2x>.png -filter Lanczos -resize 1280x800 -strip` |
| `assets/settings-mcp.png` | **Settings > MCP**: the server switched on, the stdio transport, read-only access and the tool list. Light theme. | `desktop/ref/set-mcp-on.png` |
| `assets/settings-sync.png` | **Settings > Sync & backup**: the grid of storage backends, with Hetzner selected and its region, bucket and remote folder fields. Light theme. The "backend" view of the sync section uses it. | `settings-sync.png`, rendered at 2x |
| `assets/settings-sync-2.png` | **Settings > Sync & backup**, lower part: the access keys, the endpoint, When to sync, Encrypt before upload, Test connection, Sync now and the status "Connected. Last synced 2m ago." Light theme. The "options" view of the sync section uses it. | `settings-sync-2.png`, rendered at 2x |
| `assets/welcome.png` | The welcome screen with **Create a new folder** and **Open a folder**. Light theme. | `desktop/ref/welcome.png` |
| `assets/og.png` | The social image, 1200 x 630. It shows the lockup, the tagline and `editor-light.png`, like the hero. | Made with Chromium and ImageMagick. See below. |
| `assets/phone-editor.png` | Android: a note in source mode. | Missing. See "Phone screenshots". |
| `assets/phone-preview.png` | Android: a note in preview. | Missing. |
| `assets/phone-switcher.png` | Android: the quick switcher. | Missing. |

Some figures zoom into one part of their screenshot, so the text stays readable. Each zoom is set in the `style` attribute of the image in `index.html`:

- `--z` is the scale and `--at` is the fixed point. They apply at all widths. The settings and welcome figures use them.
- `--z-sm` and `--at-sm` apply at 640 px and narrower. Most figures use them.

After you replace a screenshot, check that each zoom still frames the correct part of the window.

The screenshots come from the desktop app, rendered headless at 2x and scaled to 1280x800. To scale a 2x render the same way as the other screenshots, run this command:

```sh
magick settings-sync@2x.png -filter Lanczos -resize 1280x800 -strip site/assets/settings-sync.png
```

The sync section describes sync in the desktop app and the Android app. Both apps use the format in `SYNC.md`.

### Make the social image again

After you replace `assets/editor-light.png`, run these commands from the repository root. Chromium renders the card, because the wordmark needs weight 600 of the variable Instrument Sans font. ImageMagick then saves the card as an 8-bit PNG.

```sh
R=$PWD
T=$(mktemp -d)
cat > "$T/og.html" <<EOF
<!doctype html>
<meta charset="utf-8">
<style>
  @font-face { font-family: "Instrument Sans"; src: url("$R/site/fonts/instrument-sans.woff2"); font-weight: 400 700; }
  @font-face { font-family: "IBM Plex Mono"; src: url("$R/site/fonts/ibm-plex-mono-regular.woff2"); }
  html, body { margin: 0; width: 1200px; height: 630px; overflow: hidden; background: #f8f5ef; }
  .desk { position: absolute; left: 0; right: 0; top: 380px; bottom: 0; background: #dcd6ca; }
  .lockup { position: absolute; left: 60px; top: 52px; display: flex; align-items: center; gap: calc(0.923em / 4);
    font: 600 112px/0.9 "Instrument Sans"; letter-spacing: -0.05em; color: #2a2723; }
  .lockup svg { width: 0.923em; height: 0.923em; }
  .tagline { position: absolute; left: 66px; top: 186px; font: 24px "IBM Plex Mono"; color: #5b554d; }
  .shot { position: absolute; left: 64px; top: 252px; width: 1072px; border-radius: 10px; overflow: hidden;
    box-shadow: 0 0 0 1px rgba(30,20,5,.1), 0 30px 70px -20px rgba(40,28,10,.4), 0 8px 20px -8px rgba(40,28,10,.18); }
  .shot img { display: block; width: 100%; }
</style>
<div class="desk"></div>
<div class="lockup"><svg viewBox="0 0 100 100"><rect x="38" y="10" width="46" height="60" rx="6" fill="#e1dbcf"/><rect x="27" y="20" width="46" height="60" rx="6" fill="#c9bfae"/><rect x="16" y="30" width="46" height="60" rx="6" fill="#7a4fba"/></svg>quire</div>
<div class="tagline">A plain editor for folders of Markdown files.</div>
<div class="shot"><img src="$R/site/assets/editor-light.png"></div>
EOF
chromium --headless --disable-gpu --hide-scrollbars --no-first-run --user-data-dir="$T/profile" \
  --window-size=1200,630 --virtual-time-budget=3000 --screenshot="$T/og.png" "file://$T/og.html"
magick "$T/og.png" -crop 1200x630+0+0 +repage -strip -depth 8 site/assets/og.png
rm -r "$T"
```

### Make the icons again

If the brand files in `assets/brand/` change, run these commands from the repository root. Then copy the new colours into `site/favicon.svg` by hand.

```sh
magick -background none -density 960 assets/brand/icon-paper.svg -resize 32x32 -strip site/favicon-32.png
magick -background white -density 1728 assets/brand/icon-paper.svg -resize 180x180 -flatten -strip site/apple-touch-icon.png
```

### Phone screenshots

The page has a hidden section for 3 Android screenshots. It stays hidden, so the page never shows a broken image. To show it:

1. Add `assets/phone-editor.png`, `assets/phone-preview.png` and `assets/phone-switcher.png`. Use portrait images at 1080 x 2400.
2. In `index.html`, find `data-phone-shots="off"` and change it to `data-phone-shots="on"`.
3. If your images have another size, change the `width` and `height` attributes of the 3 `<img>` elements in that section.

To show 2 screenshots, delete the third `<figure class="phone">` element. On screens narrower than 520 px, the page shows only the first 2 screenshots.

## Brand

The logo comes from `assets/brand/`. The page draws the mark as inline SVG in the header, the hero and the footer, so it follows the theme. The sheet colours are the `--mark-front`, `--mark-mid` and `--mark-back` tokens in `styles.css`. The dark values come from `mark-on-ink.svg`.

The `.lockup` rule in `styles.css` sets the proportions. The mark box is `0.923em` of the wordmark size, and the gap is a quarter of the mark. To change the size of the mark, change `--mark` in that rule.

## Fonts

The page uses Newsreader, Instrument Sans and IBM Plex Mono. The font specimens use Courier Prime and Spline Sans Mono. The WOFF2 files come from the TTF files in `assets/fonts/`, converted with `woff2_compress` and not subset. Each font has its licence file in `fonts/`.

## Text style

Write new copy in short, active sentences in the present tense. Do not use em dashes, semicolons or exclamation marks. Claim only features that the app has today.
