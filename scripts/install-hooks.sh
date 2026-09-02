#!/usr/bin/env bash
#
# Deploy this repository's tracked git hooks as symlinks into the real hooks directory.
#
# Why this exists
# ---------------
# The hooks themselves live in scripts/hooks/ so they are version controlled and survive a clone, but
# git only ever runs what is in its own hooks directory, which is not. Symlinking the two together
# means editing a hook in scripts/hooks/ takes effect immediately with no reinstall, and a hook added
# to that directory later is picked up by re-running this script.
#
# A symlink is used rather than 'git config core.hooksPath scripts/hooks' because core.hooksPath
# replaces the hooks directory wholesale, which would silently disable any other hook already
# installed there. This script only ever touches the hooks it owns.
#
# Usage
# -----
#   ./scripts/install-hooks.sh              install (idempotent, safe to re-run)
#   ./scripts/install-hooks.sh --uninstall  remove only the symlinks this script created
#
# An existing REAL file in the way is never overwritten - it is moved aside to '<name>.local.bak'
# and reported, so a hand-written hook cannot be lost.

set -euo pipefail

uninstall=0
if [[ "${1:-}" == "--uninstall" ]]; then
    uninstall=1
elif [[ -n "${1:-}" ]]; then
    echo "usage: $(basename "$0") [--uninstall]" >&2
    exit 2
fi

repo_root="$(git rev-parse --show-toplevel)"
source_dir="$repo_root/scripts/hooks"

# 'git rev-parse --git-path hooks' resolves correctly for worktrees and submodules, where .git is a
# file rather than a directory and the hooks live somewhere else entirely.
hooks_dir="$(cd "$repo_root" && git rev-parse --git-path hooks)"
[[ "$hooks_dir" = /* ]] || hooks_dir="$repo_root/$hooks_dir"

if [[ ! -d "$source_dir" ]]; then
    echo "error: no tracked hooks directory at $source_dir" >&2
    exit 1
fi

mkdir -p "$hooks_dir"

# core.hooksPath overrides the hooks directory entirely, so symlinks placed here would never run.
configured_path="$(git config --get core.hooksPath || true)"
if [[ -n "$configured_path" ]]; then
    echo "warning: core.hooksPath is set to '$configured_path'."
    echo "         git will use that INSTEAD of $hooks_dir, so these symlinks will not run."
    echo "         Unset it with: git config --unset core.hooksPath"
    echo
fi

installed=0
removed=0
skipped=0

for source in "$source_dir"/*; do
    [[ -f "$source" ]] || continue
    name="$(basename "$source")"
    target="$hooks_dir/$name"

    # Relative link so the repository can be moved or cloned elsewhere without breaking.
    relative="$(realpath --relative-to="$hooks_dir" "$source")"

    if [[ $uninstall -eq 1 ]]; then
        if [[ -L "$target" && "$(readlink "$target")" == "$relative" ]]; then
            rm -f "$target"
            echo "removed  $name"
            removed=$((removed + 1))
        else
            skipped=$((skipped + 1))
        fi
        continue
    fi

    if [[ -L "$target" && "$(readlink "$target")" == "$relative" ]]; then
        echo "current  $name"
        skipped=$((skipped + 1))
        continue
    fi

    # A real file (not one of our symlinks) is someone's own hook. Never clobber it.
    if [[ -e "$target" && ! -L "$target" ]]; then
        mv "$target" "$target.local.bak"
        echo "note: existing $name moved to $name.local.bak"
    fi

    chmod +x "$source"
    ln -sfn "$relative" "$target"
    echo "linked   $name -> $relative"
    installed=$((installed + 1))
done

echo
if [[ $uninstall -eq 1 ]]; then
    echo "Uninstalled $removed hook(s); left $skipped untouched."
else
    echo "Installed $installed hook(s); $skipped already current."
    echo "Hooks directory: $hooks_dir"
fi
