#!/usr/bin/env bash
#
# Live view of a modb-app crawl's download progress.
#
# Shows, per meta data provider, how many raw files have been downloaded so far
# and how much disk they occupy - an at-a-glance progress bar for a running crawl
# without touching the crawl or turning up log verbosity. A provider stuck at
# 0 files is the tell that it still needs attention (e.g. a provider whose launch
# is disabled, or one routed through a proxy that isn't working yet).
#
# Usage:
#   ./scripts/dl-status.sh            # live view, refreshing every 5s
#   ./scripts/dl-status.sh 2          # live view, refreshing every 2s
#   ./scripts/dl-status.sh once       # print a single snapshot and exit (composable)
#
# The downloads location defaults to "$HOME/modb-data/downloads" and can be
# overridden for a non-default data directory:
#   MODB_DOWNLOADS=/path/to/downloads ./scripts/dl-status.sh
#
# This script contains NO secrets and NO host-specific values (usernames, tokens,
# directories): the default path is derived from $HOME at runtime.
#
set -euo pipefail

DOWNLOADS="${MODB_DOWNLOADS:-$HOME/modb-data/downloads}"

snapshot() {
    if [ ! -d "$DOWNLOADS" ]; then
        echo "downloads dir not found: $DOWNLOADS"
        return
    fi

    # newest week folder (crawls bucket downloads per ISO week; picking the newest
    # keeps this working across a week rollover on a long-running crawl)
    local dir
    dir=$(ls -dt "$DOWNLOADS"/*/ 2>/dev/null | head -1 || true)
    if [ -z "$dir" ]; then
        echo "no week folder yet under $DOWNLOADS"
        return
    fi

    echo "week folder: $dir"
    echo "total:       $(du -sh "$dir" 2>/dev/null | cut -f1)"
    echo
    for d in "$dir"*/; do
        [ -d "$d" ] || continue
        printf "%-24s %7s files  %8s\n" \
            "$(basename "$d")" \
            "$(find "$d" -type f 2>/dev/null | wc -l)" \
            "$(du -sh "$d" 2>/dev/null | cut -f1)"
    done
}

case "${1:-}" in
    once)
        snapshot
        ;;
    *)
        interval="${1:-5}"
        script=$(realpath "$0")
        exec watch -n"$interval" "$script" once
        ;;
esac
