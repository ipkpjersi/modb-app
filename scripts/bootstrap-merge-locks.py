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


def host_of(url):
    # Strip a leading "www." so --only-hosts can use the bare provider hostname.
    netloc = urlparse(url).netloc.lower()
    return netloc[4:] if netloc.startswith("www.") else netloc


def load_entries(path):
    with open(path, encoding="utf-8") as handle:
        doc = json.load(handle)
    # anime-offline-database files wrap the entries in a top-level "data" array.
    return doc["data"] if isinstance(doc, dict) and "data" in doc else doc


def build_groups(entries, only_hosts):
    groups = []
    for entry in entries:
        sources = entry.get("sources", [])
        if only_hosts is not None:
            sources = [s for s in sources if host_of(s) in only_hosts]
        # A group is only meaningful with two or more sources to lock together.
        if len(sources) >= 2:
            groups.append(sorted(sources))
    return groups


def single_source_urls(entries):
    # URLs of entries that have exactly one source, i.e. entries this dataset considers standalone.
    result = set()
    for entry in entries:
        sources = entry.get("sources", [])
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
        sources = entry.get("sources", [])
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
    parser.add_argument("--fork-db", default=None, help="Optional path to the fork's dataset JSON; prints how many of its single-source entries would merge. Also required for --checked-isolated.")
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

    entries = load_entries(args.official_db)
    groups = build_groups(entries, only_hosts)
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
