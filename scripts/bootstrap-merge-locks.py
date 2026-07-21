#!/usr/bin/env python3
"""
Generate a merge.lock from a known-good anime-offline-database JSON file.

Every entry in an anime-offline-database file carries a `sources` array that lists all provider URLs
for the same anime. That array is exactly a merge-lock group: a set of source URLs confirmed to be the
same anime. So a merge.lock can be reconstructed directly from any good dataset (for example the upstream
manami-project release), without reviewing anything by hand.

The upstream project does not distribute its raw merge.lock, which is why a fork starts at 0% reviewed.
But the published dataset encodes the identical groupings, so this script rebuilds them from that public
output. Import the result into the DCS directory and run the app / analyzer reprocess to force-merge the
fork's fragmented single-source entries into the upstream grouping.

Usage:
    scripts/bootstrap-merge-locks.py OFFICIAL_DB.json -o merge.lock
    scripts/bootstrap-merge-locks.py OFFICIAL_DB.json -o merge.lock --only-hosts myanimelist.net,anilist.co,kitsu.app,livechart.me,animenewsnetwork.com
    scripts/bootstrap-merge-locks.py OFFICIAL_DB.json -o merge.lock --fork-db ~/anime-offline-database/anime-offline-database-minified.json

This script never writes into the DCS directory itself. Review the generated file, then copy it to
<downloadControlStateDirectory>/merge.lock yourself.
"""

import argparse
import json
import sys
from urllib.parse import urlparse


# Hosts that appear in the dataset's `sources` but are not crawlable metadata providers. animecountdown.com is a
# derived countdown link the app adds at build time, not a real source. It must be excluded from both merge.lock
# and checked-isolated: it is not a source to lock, and the app's dead-entries validator has no case for it and
# throws on it. Excluding it also means an entry like [mal, animecountdown] is correctly treated as MAL-only.
EXCLUDED_HOSTS = {"animecountdown.com"}


def host_of(url):
    # Strip a leading "www." so --only-hosts can use the bare provider hostname.
    netloc = urlparse(url).netloc.lower()
    return netloc[4:] if netloc.startswith("www.") else netloc


def real_sources(entry, only_hosts, present=None):
    result = []
    for s in entry.get("sources", []):
        h = host_of(s)
        if h in EXCLUDED_HOSTS:
            continue
        if only_hosts is not None and h not in only_hosts:
            continue
        # present: restrict to sources the fork actually crawled. A merge.lock source with no crawled entry has
        # no DCS file, and the dead-entries validator flags it as dead and aborts, so those must be dropped.
        if present is not None and s not in present:
            continue
        result.append(s)
    return result


def all_sources(entries):
    result = set()
    for entry in entries:
        result.update(entry.get("sources", []))
    return result


def load_entries(path):
    with open(path, encoding="utf-8") as handle:
        doc = json.load(handle)
    # anime-offline-database files wrap the entries in a top-level "data" array.
    return doc["data"] if isinstance(doc, dict) and "data" in doc else doc


def build_groups(entries, only_hosts, present=None):
    groups = []
    for entry in entries:
        sources = real_sources(entry, only_hosts, present)
        # A group is only meaningful with two or more real sources to lock together.
        if len(sources) >= 2:
            groups.append(sorted(sources))
    return groups


def single_source_urls(entries):
    # URLs of entries that have exactly one real source, i.e. entries this dataset considers standalone
    # (ignoring derived hosts like animecountdown.com).
    result = set()
    for entry in entries:
        sources = real_sources(entry, None)
        if len(sources) == 1:
            result.add(sources[0])
    return result


def check_no_duplicate_source(groups):
    seen = {}
    for group in groups:
        for source in group:
            if source in seen:
                raise SystemExit(
                    f"error: source [{source}] appears in more than one group; the input dataset is inconsistent."
                )
            seen[source] = True
    return len(seen)


def report_fork_impact(groups, fork_db_path):
    locked = set()
    for group in groups:
        locked.update(group)

    entries = load_entries(fork_db_path)
    single_source = 0
    single_source_that_would_merge = 0
    for entry in entries:
        sources = real_sources(entry, None)
        if len(sources) == 1:
            single_source += 1
            if sources[0] in locked:
                single_source_that_would_merge += 1

    print(
        f"fork impact: {single_source_that_would_merge} of {single_source} single-source entries "
        f"in the fork are covered by a group and would merge.",
        file=sys.stderr,
    )


def main():
    parser = argparse.ArgumentParser(description="Generate merge.lock from a known-good anime-offline-database file.")
    parser.add_argument("official_db", help="Path to the known-good anime-offline-database JSON (e.g. the upstream release).")
    parser.add_argument("-o", "--output", default="merge.lock", help="Where to write the generated merge.lock (default: ./merge.lock).")
    parser.add_argument(
        "--only-hosts",
        default=None,
        help="Comma-separated hostnames to keep (e.g. myanimelist.net,anilist.co). Sources from other providers "
        "are dropped and groups that fall below two sources are skipped. Default: keep every source.",
    )
    parser.add_argument("--fork-db", default=None, help="Optional path to the fork's dataset JSON; prints how many of its single-source entries would merge. Also required for --checked-isolated and --restrict-to-fork.")
    parser.add_argument(
        "--restrict-to-fork",
        action="store_true",
        help="Keep only merge-lock sources that already exist in --fork-db (i.e. providers the fork actually "
        "crawls). Prevents the dead-entries validator from aborting on uncrawled providers. Re-run without this "
        "(or re-run after expanding your crawl coverage) to widen the groups later.",
    )
    parser.add_argument(
        "--checked-isolated",
        default=None,
        help="Optional path to also write checked-isolated-entries.txt. Certifies only URLs that are single-source "
        "in BOTH the official DB and the fork (requires --fork-db), so an entry that gained a provider in the fork's "
        "newer crawl is not wrongly frozen as never-merge.",
    )
    args = parser.parse_args()

    only_hosts = None
    if args.only_hosts:
        only_hosts = {h.strip().lower() for h in args.only_hosts.split(",") if h.strip()}

    present = None
    if args.restrict_to_fork:
        if not args.fork_db:
            raise SystemExit("error: --restrict-to-fork requires --fork-db.")
        present = all_sources(load_entries(args.fork_db))

    entries = load_entries(args.official_db)
    groups = build_groups(entries, only_hosts, present)
    total_sources = check_no_duplicate_source(groups)

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump({"mergeLocks": groups}, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"wrote {len(groups)} merge-lock groups ({total_sources} sources) to {args.output}", file=sys.stderr)
    if args.fork_db:
        report_fork_impact(groups, args.fork_db)

    if args.checked_isolated:
        if not args.fork_db:
            raise SystemExit("error: --checked-isolated requires --fork-db so only entries both sides agree are standalone get certified.")
        official_single = single_source_urls(entries)
        fork_single = single_source_urls(load_entries(args.fork_db))
        # Certify only where the fork and the official DB both consider the entry standalone. This is disjoint from
        # the merge-lock groups by construction (those come from multi-source official entries).
        isolated = sorted(fork_single & official_single)
        with open(args.checked_isolated, "w", encoding="utf-8") as handle:
            handle.write("\n".join(isolated))
            if isolated:
                handle.write("\n")
        print(
            f"wrote {len(isolated)} checked-isolated entries to {args.checked_isolated} "
            f"(fork single-source {len(fork_single)}, of which {len(isolated)} confirmed standalone by the official DB)",
            file=sys.stderr,
        )

    print(
        "next: review the file, then copy it to <downloadControlStateDirectory>/merge.lock and run "
        "[r] Reprocess merging (or a full app run) to apply it.",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
