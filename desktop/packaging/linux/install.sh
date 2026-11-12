#!/bin/sh
# Installs Quire for the current user from this folder: the binary, its icon and its
# launcher entry. Run it from the folder you unpacked.
set -e
cd "$(dirname "$0")"
install -Dm755 quire "$HOME/.local/bin/quire"
install -Dm644 icons/quire.svg "$HOME/.local/share/icons/hicolor/scalable/apps/quire.svg"
install -Dm644 icons/quire-16.svg "$HOME/.local/share/icons/hicolor/16x16/apps/quire.svg"
install -Dm644 quire.desktop "$HOME/.local/share/applications/quire.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" || true
echo "Installed. Run quire, or open Quire from the app launcher."
echo "If quire is not found, add ~/.local/bin to your PATH."
