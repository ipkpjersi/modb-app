#!/usr/bin/env bash
#
# Drive the analyzer's [r] Reprocess merging non-interactively, then verify the result.
#
# Why this exists
# ---------------
# Reprocessing re-merges the DCS entries into the dataset using the current merge.lock and
# checked-isolated-entries.txt (for example after re-running scripts/bootstrap-merge-locks.py). Doing it
# by hand means piping "r" then "q" into the analyzer, watching a log, and then hand-checking the result
# because the analyzer's own final validator, SourcesConsistencyValidationPostProcessor, cannot run
# cleanly on a standalone reprocess: it looks for *.conv files in the CURRENT week's working dir, but the
# *.conv files belong to the week the crawl actually ran. After a week rollover (any run on or after the
# Monday that starts a new ISO week) that dir is empty, so it aborts with "No sources in [*.conv] files."
# AFTER the dataset has already been written correctly. This script:
#   1. backs up the DCS merge.lock and checked-isolated-entries.txt (timestamped),
#   2. runs the reprocess and logs it,
#   3. treats ONLY that one conv-files exception as a known non-fatal week-rollover false alarm - any
#      other exception is a real failure,
#   4. captures the DCS locks back into the repo's review-data/ so the committed copy always reflects the
#      locks that produced this dataset (on by default; see --no-update-review-data),
#   5. runs scripts/verify-dataset.py, which performs the dataset-vs-DCS consistency check the aborted
#      validator skipped, plus entry count, reviewed% and provider coverage.
#
# Layout matches run-analyzer.sh: modb-analyzer.jar and config.toml live in a run directory, default
# "$HOME/modb-run", overridable with MODB_RUN_DIR. This script contains NO secrets and NO host-specific
# values.
#
# Usage:
#   ./run-reprocess.sh                       # backup, reprocess, capture locks into review-data/, verify
#   ./run-reprocess.sh --deploy-locks        # copy review-data/ locks into the DCS first, then reprocess
#   ./run-reprocess.sh --no-update-review-data  # do not capture the DCS locks back into review-data/
#   ./run-reprocess.sh --skip-verify         # skip the final verify-dataset.py step
#   MODB_RUN_DIR=/path ./run-reprocess.sh
#
# It does NOT run the bootstrap. Regenerate merge.lock with scripts/bootstrap-merge-locks.py (writing to
# review-data/, the canonical committed copy), then either copy the files into the DCS directory yourself
# or pass --deploy-locks to have this script copy review-data/merge.lock and checked-isolated-entries.txt
# into the DCS directory before reprocessing. --deploy-locks is off by default so a stale review-data/ can
# never silently overwrite the live DCS locks. The reverse capture (DCS -> review-data/, after a successful
# reprocess) IS on by default so the committed copy is always ready to push; disable it with
# --no-update-review-data, and note --deploy-locks implies that (there is nothing new to capture back).

set -euo pipefail

SKIP_VERIFY=0
DEPLOY_LOCKS=0
UPDATE_REVIEW_DATA=1
for arg in "$@"; do
    case "$arg" in
        --skip-verify) SKIP_VERIFY=1 ;;
        --deploy-locks) DEPLOY_LOCKS=1 ;;
        --no-update-review-data) UPDATE_REVIEW_DATA=0 ;;
        -h|--help) sed -n '2,41p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "error: unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# --deploy-locks copies review-data -> DCS, making the repo the source of truth for this run. The reprocess
# never writes merge.lock (it only reads it), so the reverse capture would only copy the just-deployed bytes
# straight back. Skip the capture in that case to avoid a misleading no-op; --deploy-locks therefore implies
# --no-update-review-data.
if [[ "$DEPLOY_LOCKS" -eq 1 ]]; then
    UPDATE_REVIEW_DATA=0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/jdk-25}"
JAVA_BIN="$JAVA_HOME/bin/java"
RUN_DIR="${MODB_RUN_DIR:-$HOME/modb-run}"

[[ -x "$JAVA_BIN" ]] || { echo "error: no JDK at $JAVA_BIN (set JAVA_HOME or install JDK 25)" >&2; exit 1; }
[[ -d "$RUN_DIR" ]] || { echo "error: run directory not found: $RUN_DIR (set MODB_RUN_DIR)" >&2; exit 1; }
[[ -f "$RUN_DIR/config.toml" ]] || { echo "error: config.toml not found in $RUN_DIR" >&2; exit 1; }
[[ -f "$RUN_DIR/modb-analyzer.jar" ]] || { echo "error: modb-analyzer.jar not found in $RUN_DIR" >&2; exit 1; }

# Resolve the DCS directory from config.toml so the backups land next to the files the reprocess reads.
DCS_DIR="$(sed -n 's/^[[:space:]]*downloadControlStateDirectory[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$RUN_DIR/config.toml" | head -1)"
[[ -n "$DCS_DIR" && -d "$DCS_DIR" ]] || { echo "error: could not resolve downloadControlStateDirectory from config.toml" >&2; exit 1; }

TS="$(date +%Y%m%d-%H%M%S)"

# 1) Back up the review data the reprocess consumes, so a bad merge.lock can be rolled back.
for f in merge.lock checked-isolated-entries.txt; do
    if [[ -f "$DCS_DIR/$f" ]]; then
        cp -p "$DCS_DIR/$f" "$DCS_DIR/$f.bak-$TS"
        echo "backed up $DCS_DIR/$f -> $f.bak-$TS"
    fi
done

# 2) Optionally deploy the canonical merge.lock / checked-isolated-entries.txt from the repo's review-data/
#    into the DCS directory, so the reprocess consumes the version tracked in git rather than whatever
#    currently sits in the DCS. This runs AFTER the backup above, so the previous DCS locks are always
#    recoverable. Off by default (see --deploy-locks) to avoid overwriting live DCS locks with a stale copy.
REVIEW_DATA_DIR="$SCRIPT_DIR/../review-data"
if [[ "$DEPLOY_LOCKS" -eq 1 ]]; then
    for f in merge.lock checked-isolated-entries.txt; do
        [[ -f "$REVIEW_DATA_DIR/$f" ]] || { echo "error: --deploy-locks given but $REVIEW_DATA_DIR/$f not found" >&2; exit 1; }
    done
    for f in merge.lock checked-isolated-entries.txt; do
        cp -p "$REVIEW_DATA_DIR/$f" "$DCS_DIR/$f"
        echo "deployed review-data/$f -> $DCS_DIR/$f"
    done
fi

# 3) Drive the reprocess. "r" selects Reprocess merging, "q" quits the menu afterwards.
LOG="$RUN_DIR/reprocess-$TS.log"
ln -sf "$LOG" "$RUN_DIR/reprocess-latest.log"
echo "reprocessing (log: $LOG) ..."
cd "$RUN_DIR"
# The analyzer exits 0 even when it throws, so success is judged from the log, not the exit code.
printf 'r\nq\n' | "$JAVA_BIN" \
    -Djava.net.preferIPv6Addresses=true \
    -Djava.net.preferIPv4Stack=false \
    -jar modb-analyzer.jar > "$LOG" 2>&1 || true

# 4) Judge the outcome from the log.
CONV_ROLLOVER='No sources in \[\*\.conv\] files\.'
if grep -q "Reprocessing merging complete" "$LOG"; then
    echo "reprocess completed cleanly:"
    grep "Reprocessing merging complete" "$LOG"
elif grep -qE "Exception in thread" "$LOG"; then
    # A thrown exception is only acceptable if it is exactly the conv-files week-rollover false alarm and
    # nothing else was thrown. Any other exception means the dataset may be wrong or half-written.
    OTHER="$(grep "Exception in thread" "$LOG" | grep -vE "$CONV_ROLLOVER" || true)"
    if grep -qE "$CONV_ROLLOVER" "$LOG" && [[ -z "$OTHER" ]]; then
        echo "note: reprocess hit the known week-rollover validator false alarm (No sources in [*.conv] files)."
        echo "      the dataset is written before that check runs; verifying below."
    else
        echo "error: reprocess failed with an unexpected exception:" >&2
        tail -25 "$LOG" >&2
        exit 1
    fi
else
    echo "error: reprocess did not report completion and no known exception was found; see $LOG" >&2
    tail -25 "$LOG" >&2
    exit 1
fi

# 5) Capture the DCS locks back into the repo's review-data/ so the committed copy always reflects the
#    locks that just produced this dataset, ready to commit and push. This is the reverse of --deploy-locks
#    and runs only after a successful reprocess (the failure paths above exit first). On by default; pass
#    --no-update-review-data to skip it (--deploy-locks implies that). It is also skipped automatically when
#    review-data/ is absent (for example when running from a deployed copy of the script outside the repo).
if [[ "$UPDATE_REVIEW_DATA" -eq 1 ]]; then
    if [[ -d "$REVIEW_DATA_DIR" ]]; then
        for f in merge.lock checked-isolated-entries.txt; do
            if [[ -f "$DCS_DIR/$f" ]]; then
                cp -p "$DCS_DIR/$f" "$REVIEW_DATA_DIR/$f"
                echo "captured $DCS_DIR/$f -> review-data/$f"
            fi
        done
    else
        echo "note: review-data/ not found at $REVIEW_DATA_DIR; skipping capture into review-data."
    fi
fi

# 6) Verify the written dataset against the DCS (the check the in-app validator skipped).
if [[ "$SKIP_VERIFY" -eq 1 ]]; then
    echo "--skip-verify given; not running verify-dataset.py"
    exit 0
fi
echo
echo "=== verify-dataset.py ==="
# verify-dataset.py reads the same config.toml via MODB_RUN_DIR, so it points at the same output and DCS.
MODB_RUN_DIR="$RUN_DIR" exec python3 "$SCRIPT_DIR/verify-dataset.py"
