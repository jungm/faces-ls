#!/usr/bin/env bash
# Runs faces-ls from the distribution zip in a headless Neovim (0.11+, no plugins) against samples/multi/web,
# with jdtls loading the faces-jdt bundle, and prints diagnostics, completion, definitions, hover and semantic tokens.
#
# JDTLS: jdtls install directory with bin/jdtls (default: the one installed by Mason; it is copied, not modified).
# FACES_TEST_JAVA_HOME: the JDK that runs faces-ls and jdtls; it has to be the version this jdtls needs (25 for current
# releases, while the build needs 21). Default: the current SDKMAN Java.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build/nvim-test"
ZIP="$(ls "$ROOT"/faces-ls-dist/target/faces-ls-*.zip | head -1)"
JDTLS_SOURCE="${JDTLS:-${XDG_DATA_HOME:-$HOME/.local/share}/nvim/mason/packages/jdtls}"

# servers of an earlier run that the client did not shut down
pkill -f "^[^ ]*/java .*build/nvim-test/" || true
rm -rf "$WORK/dist" "$WORK/jdtls-data" "$WORK"/faces-ls.log* "$WORK/out.txt"
mkdir -p "$WORK/dist" "$WORK/xdg"
unzip -q "$ZIP" -d "$WORK/dist"
if [ ! -d "$WORK/jdtls" ]; then
  if [ ! -x "$JDTLS_SOURCE/bin/jdtls" ]; then
    echo "No jdtls in $JDTLS_SOURCE, set JDTLS to a jdtls install directory" >&2
    exit 1
  fi
  cp -r "$JDTLS_SOURCE" "$WORK/jdtls"
fi

export JAVA_HOME="${FACES_TEST_JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
env FACES_DIST="$WORK/dist" JDTLS="$WORK/jdtls/bin/jdtls" WORK="$WORK" \
  XDG_CONFIG_HOME="$WORK/xdg/config" XDG_DATA_HOME="$WORK/xdg/data" XDG_STATE_HOME="$WORK/xdg/state" \
  XDG_CACHE_HOME="$WORK/xdg/cache" SAMPLE="$ROOT/samples/multi/web" LOG="$WORK/faces-ls.log" OUT="$WORK/out.txt" \
  timeout 240 nvim --headless -u "$ROOT/scripts/nvim/init.lua" -i NONE --noplugin -c "luafile $ROOT/scripts/nvim/test.lua"
cat "$WORK/out.txt"
# jdtls does not exit when Neovim quits without shutting it down
pkill -f "^[^ ]*/java .*build/nvim-test/jdtls/" || true
