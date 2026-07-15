#!/usr/bin/env bash
#
# Launch modb-analyzer, the interactive dataset review / merge-lock tool.
#
# Unlike run-app.sh this is a hands-on, interactive program: it loads the dataset
# and DCS pointed at by config.toml and walks you through duplicate and merge-lock
# review. It does NOT crawl, rotate IPv6, use sudo, or send crash alerts, so it
# needs none of run-app.sh's machinery.
#
# Run it in a terminal that supports OSC 8 hyperlinks (iTerm2, Kitty, Windows
# Terminal, GNOME Terminal, VS Code) so the candidate provider URLs it prints for
# each entry are clickable. Without OSC 8 support the URLs still print as plain,
# copy-pasteable text.
#
# Layout matches run-app.sh: modb-analyzer.jar and config.toml live in a run
# directory, which defaults to "$HOME/modb-run" and can be overridden:
#   MODB_RUN_DIR=/path/to/run-dir ./scripts/run-analyzer.sh
#
# This script contains NO secrets and NO host-specific values.

set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/opt/jdk-25}"
JAVA_BIN="$JAVA_HOME/bin/java"
RUN_DIR="${MODB_RUN_DIR:-$HOME/modb-run}"

if [[ ! -x "$JAVA_BIN" ]]; then
    echo "error: no JDK at $JAVA_BIN (set JAVA_HOME or install JDK 25)" >&2
    exit 1
fi

if [[ ! -d "$RUN_DIR" ]]; then
    echo "error: run directory not found: $RUN_DIR (set MODB_RUN_DIR)" >&2
    exit 1
fi

# Run from the run directory so config.toml and modb-analyzer.jar are picked up there.
cd "$RUN_DIR"

if [[ ! -f config.toml ]]; then
    echo "error: config.toml not found in $RUN_DIR" >&2
    exit 1
fi

if [[ ! -f modb-analyzer.jar ]]; then
    echo "error: modb-analyzer.jar not found in $RUN_DIR" >&2
    exit 1
fi

# exec directly (no script(1) pty wrapper): the analyzer needs the real terminal
# for interactive input and clickable OSC 8 links, and there is no sudo prompt to
# protect. The IPv6 flags mirror run-app.sh for consistency; the analyzer does not
# crawl, so they have no practical effect here.
exec "$JAVA_BIN" \
    -Djava.net.preferIPv6Addresses=true \
    -Djava.net.preferIPv4Stack=false \
    -jar modb-analyzer.jar "$@"
