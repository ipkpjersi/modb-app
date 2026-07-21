#!/usr/bin/env bash
#
# Launch modb-app with the correct JDK and IPv6 JVM flags, and alert on a crash.
#
# Run it inside tmux so the JVM survives an SSH disconnect:
#   tmux new -s crawl
#   ./scripts/run-app.sh
#
# You'll be prompted for your sudo password once at startup (used for the
# `ip -6 addr` rotation). Stop the crawl with a SINGLE Ctrl+C and wait for the
# "Restored the original IPv6 address configuration." log line before assuming
# networking is back to normal. Never hard-kill it (kill -9 skips the restore).
#
# On a hard crash - the JVM exiting non-zero when it was NOT a manual Ctrl+C - a
# Discord webhook alert is sent containing the tail of the run output. A manual
# Ctrl+C is treated as an expected stop and does NOT alert.
#
# Layout: the modb-app.jar and config.toml live in a run directory, which
# defaults to "$HOME/modb-run" and can be overridden:
#   MODB_RUN_DIR=/path/to/run-dir ./scripts/run-app.sh
#
# The Discord webhook URL is read from the MODB_DISCORD_WEBHOOK environment
# variable, or from a "discord-webhook.txt" file inside the run directory. If
# neither is present the alert is skipped. Keep that file out of version control.
#
# This script contains NO secrets and NO host-specific values (usernames, tokens,
# webhook URLs, IP prefixes, directories). Those are provided via the environment
# or files in the run directory at runtime.

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

# Run from the run directory so config.toml and modb-app.jar are picked up there.
cd "$RUN_DIR"

if [[ ! -f config.toml ]]; then
    echo "error: config.toml not found in $RUN_DIR" >&2
    exit 1
fi

if [[ ! -f modb-app.jar ]]; then
    echo "error: modb-app.jar not found in $RUN_DIR" >&2
    exit 1
fi

# Resolve the Discord webhook URL (optional). Environment variable wins over the
# file so a one-off run can override it without editing anything on disk.
WEBHOOK_URL="${MODB_DISCORD_WEBHOOK:-}"
if [[ -z "$WEBHOOK_URL" && -f discord-webhook.txt ]]; then
    WEBHOOK_URL="$(< discord-webhook.txt)"
fi

# Send a crash alert to Discord with the tail of the captured run output.
notify_crash() {
    local status="$1" logfile="$2"

    if [[ -z "$WEBHOOK_URL" ]]; then
        echo "warn: no Discord webhook configured (MODB_DISCORD_WEBHOOK or discord-webhook.txt); skipping crash alert." >&2
        return
    fi

    # Keep the last chunk of output only so the payload stays under Discord's
    # 2000 character message limit (leaving headroom for the surrounding text).
    # Strip the carriage returns and the header/footer lines that script(1) adds.
    local tail_txt
    tail_txt="$(tr -d '\r' < "$logfile" 2>/dev/null | grep -vE '^Script (started|done) on ' | tail -n 30 || true)"
    tail_txt="${tail_txt: -1500}"

    local content
    content="$(printf ':rotating_light: **modb-app crashed** on `%s`\nexit status: `%s`\ntime: `%s`\n```\n%s\n```' \
        "$(hostname)" "$status" "$(date '+%Y-%m-%d %H:%M:%S %Z')" "$tail_txt")"

    if curl -sf --max-time 15 -H 'Content-Type: application/json' \
        -d "$(jq -Rn --arg c "$content" '{content:$c}')" \
        "$WEBHOOK_URL" >/dev/null; then
        echo "Sent Discord crash alert." >&2
    else
        echo "warn: failed to send Discord crash alert." >&2
    fi
}

# Track manual interruption so a deliberate stop is not reported as a crash.
#
# This trap only covers a signal aimed at THIS script (e.g. `kill %1`). It does NOT catch a typed Ctrl+C:
# script(1) below puts the terminal into raw mode, so the ^C is forwarded as a byte into the inner pty and
# raises SIGINT there, reaching the JVM only - this shell never sees the signal. That case is recognised
# from the JVM's exit status instead, see STOPPED_BY_SIGNAL below.
INTERRUPTED=0
trap 'INTERRUPTED=1' INT TERM

# Capture the run output so it can be attached to a crash alert, while still
# streaming it to the terminal. Removed automatically on exit.
CAPTURE="$(mktemp -t modb-app.XXXXXX.log)"
trap 'rm -f "$CAPTURE"' EXIT

# Run the JVM under a pseudo-terminal via script(1), not a pipe. The app shells
# out to `sudo` for the IPv6 rotation; piping the JVM's stdout/stderr would leave
# sudo without a tty on those fds, so it could not disable echo and would print
# the typed password. script(1) gives the child a real pty (sudo hides the
# password) while still capturing all console output to CAPTURE for the crash
# alert. errexit is lifted so a non-zero exit does not abort before we can react;
# `-e` makes script return the child's own exit status.
set +e
run_cmd="$(printf '%q ' "$JAVA_BIN" \
    -Djava.net.preferIPv6Addresses=true \
    -Djava.net.preferIPv4Stack=false \
    -jar modb-app.jar "$@")"
script -q -e -c "$run_cmd" "$CAPTURE"
STATUS=$?
set -e

# A process killed by a signal exits with 128 + signal number. 130 (SIGINT) is the documented single Ctrl+C
# stop, and 143 (SIGTERM) is an equally deliberate `kill`. Neither is a crash, and neither reaches the trap
# above when the JVM runs under script(1) - so they have to be recognised here or every manual stop pages you.
# A hard kill -9 (137) still alerts: it skips the JVM's shutdown hook, so the IPv6 restore did not run.
STOPPED_BY_SIGNAL=0
if [[ "$STATUS" -eq 130 || "$STATUS" -eq 143 ]]; then
    STOPPED_BY_SIGNAL=1
fi

if [[ "$STATUS" -ne 0 && "$INTERRUPTED" -eq 0 && "$STOPPED_BY_SIGNAL" -eq 0 ]]; then
    notify_crash "$STATUS" "$CAPTURE"
fi

exit "$STATUS"
