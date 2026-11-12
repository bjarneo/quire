#!/bin/sh
# Installs Quire for the current user: the binary, its icon and its launcher entry.
set -e
cd "$(dirname "$0")"
cargo build --release
install -Dm755 target/release/quire "$HOME/.local/bin/quire"
install -Dm644 packaging/quire.svg "$HOME/.local/share/icons/hicolor/scalable/apps/quire.svg"
# Below 24 px the icon drops its back sheet, so small sizes get their own drawing.
install -Dm644 packaging/quire-16.svg "$HOME/.local/share/icons/hicolor/16x16/apps/quire.svg"
for size in 32 48 64 128 256 512; do
  magick -background none -density 600 packaging/quire.svg -resize ${size}x${size} "$HOME/.local/share/icons/hicolor/${size}x${size}/apps/quire.png" 2>/dev/null \
    || install -Dm644 packaging/quire.svg "$HOME/.local/share/icons/hicolor/${size}x${size}/apps/quire.svg"
done
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" || true
install -Dm644 packaging/quire.desktop "$HOME/.local/share/applications/quire.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" || true
echo "Installed. Run quire, or open Quire from the app launcher."
