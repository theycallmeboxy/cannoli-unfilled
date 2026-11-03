#!/bin/sh
# Builds the Kitchen web page from kitchen/ into app/src/main/assets/kitchen, which the APK
# serves. Needs bun (the build container has it). Run from anywhere.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/kitchen"
bun install --frozen-lockfile
# build-only skips vue-tsc, which cannot run under bun (Volar .vue resolution breaks).
bun run build-only
rm -rf "$root/app/src/main/assets/kitchen"
mkdir -p "$root/app/src/main/assets/kitchen"
cp -R dist/. "$root/app/src/main/assets/kitchen/"
