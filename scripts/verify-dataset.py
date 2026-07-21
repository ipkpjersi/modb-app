#!/usr/bin/env python3
"""
Verify a generated anime-offline-database dataset against the DCS files and the review data.

Why this exists
---------------
A normal crawl ends with SourcesConsistencyValidationPostProcessor, which cross-checks the sources in
the intermediate *.conv files, the *.dcs files and the dataset. A standalone analyzer reprocess (the
[r] option) cannot run that check meaningfully: it works off the *.dcs files only, and the *.conv files
belong to the week the crawl actually ran, so after a week rollover the current-week working dir is
empty and the post-processor aborts with "No sources in [*.conv] files." before it ever compares the
dataset to the DCS. This script performs the part that still matters for a reprocess - dataset vs DCS
consistency - plus the entry count, the reviewed percentage and the per-provider coverage, so a
reprocess (or any crawl) can be confirmed sound without the in-app validator.

The reviewed percentage mirrors DefaultReadmeCreator.getNumberOfEntriesAndPercentReviewed exactly:
animecountdown.com sources are ignored; a single-source entry is reviewed if it is in
checked-isolated-entries.txt or every source is in a merge lock; a multi-source entry is reviewed if
every source is in a merge lock. hasMergeLock checks each source individually, so "in a merge lock"
means the source appears in any merge-lock group. The headline percent is truncated to an int, as the
README does.

Usage
-----
  ./verify-dataset.py                 # read paths from config.toml in MODB_RUN_DIR (default ~/modb-run)
  ./verify-dataset.py --output-dir /path/to/anime-offline-database --dcs-dir /path/to/dcs
  ./verify-dataset.py --min-entries 40000   # also fail if the dataset has fewer entries than this

Exit status is 0 when the dataset is consistent with the DCS (and any --min-entries floor is met),
and 1 otherwise, so it can gate a reprocess wrapper.

This script contains NO secrets and NO host-specific values; all paths come from config.toml or flags.
"""

import argparse
import glob
import json
import os
import pathlib
import re
import sys
from urllib.parse import urlparse

OUTPUT_DIRECTORY_PATTERN = re.compile(r'^\s*outputDirectory\s*=\s*"([^"]+)"', re.MULTILINE)
DCS_DIRECTORY_PATTERN = re.compile(r'^\s*downloadControlStateDirectory\s*=\s*"([^"]+)"', re.MULTILINE)

# animecountdown.com is a derived countdown link the app adds at build time, not a crawled source. It is
# excluded from every source comparison, exactly as the app's own validators and readme creator do.
EXCLUDED_HOSTS = {"animecountdown.com"}


def host_of(url):
    netloc = urlparse(url).netloc.lower()
    return netloc[4:] if netloc.startswith("www.") else netloc


def config_path():
    run_dir = pathlib.Path(os.environ.get("MODB_RUN_DIR", pathlib.Path.home() / "modb-run"))
    config = run_dir / "config.toml"
    if not config.is_file():
        sys.exit(f"error: no config.toml in {run_dir} (set MODB_RUN_DIR or pass --output-dir/--dcs-dir)")
    return config


def value_from_config(pattern, key, override):
    if override is not None:
        return pathlib.Path(override)
    config = config_path()
    match = pattern.search(config.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"error: no '{key}' in {config} (pass the matching flag)")
    return pathlib.Path(match.group(1))


def dataset_sources(dataset_file):
    if not dataset_file.is_file():
        sys.exit(f"error: dataset file not found: {dataset_file}")
    data = json.loads(dataset_file.read_text(encoding="utf-8"))["data"]
    sources = set()
    for entry in data:
        for source in entry.get("sources", []):
            if host_of(source) not in EXCLUDED_HOSTS:
                sources.add(source)
    return data, sources


def dcs_sources(dcs_dir):
    sources = set()
    files = 0
    for path in glob.glob(str(dcs_dir / "*" / "*.dcs")):
        files += 1
        anime = json.loads(pathlib.Path(path).read_text(encoding="utf-8")).get("_anime", {})
        for source in anime.get("sources", []):
            if host_of(source) not in EXCLUDED_HOSTS:
                sources.add(source)
    return files, sources


def load_review_data(dcs_dir):
    locked = set()
    merge_lock = dcs_dir / "merge.lock"
    if merge_lock.is_file():
        for group in json.loads(merge_lock.read_text(encoding="utf-8")).get("mergeLocks", []):
            locked.update(group)
    checked = set()
    checked_file = dcs_dir / "checked-isolated-entries.txt"
    if checked_file.is_file():
        checked = {line.strip() for line in checked_file.read_text(encoding="utf-8").splitlines() if line.strip()}
    return locked, checked


def reviewed_stats(data, locked, checked):
    unreviewed = 0
    for entry in data:
        real = [s for s in entry.get("sources", []) if host_of(s) not in EXCLUDED_HOSTS]
        if len(real) == 1:
            is_reviewed = real[0] in checked or real[0] in locked
        else:
            is_reviewed = all(s in locked for s in real)
        if not is_reviewed:
            unreviewed += 1
    total = len(data)
    percent = 100.0 - (unreviewed / total * 100.0) if total else 0.0
    return total, unreviewed, percent


def main():
    parser = argparse.ArgumentParser(description="Verify a dataset against the DCS files and review data.")
    parser.add_argument("--output-dir", help="dataset output directory (default: outputDirectory from config.toml)")
    parser.add_argument("--dcs-dir", help="DCS directory (default: downloadControlStateDirectory from config.toml)")
    parser.add_argument("--dataset-file", default="anime-offline-database-minified.json",
                        help="dataset file name inside the output directory (default: %(default)s)")
    parser.add_argument("--min-entries", type=int, default=0,
                        help="fail if the dataset has fewer than this many entries (default: no floor)")
    parser.add_argument("--sample", type=int, default=5, help="how many mismatching sources to print (default: 5)")
    args = parser.parse_args()

    output_dir = value_from_config(OUTPUT_DIRECTORY_PATTERN, "outputDirectory", args.output_dir)
    dcs_dir = value_from_config(DCS_DIRECTORY_PATTERN, "downloadControlStateDirectory", args.dcs_dir)
    dataset_file = output_dir / args.dataset_file

    data, in_dataset = dataset_sources(dataset_file)
    dcs_files, in_dcs = dcs_sources(dcs_dir)
    locked, checked = load_review_data(dcs_dir)
    total, unreviewed, percent = reviewed_stats(data, locked, checked)

    only_dataset = in_dataset - in_dcs
    only_dcs = in_dcs - in_dataset
    consistent = not only_dataset and not only_dcs
    entries_ok = total >= args.min_entries

    print(f"dataset file       : {dataset_file}")
    print(f"dcs directory      : {dcs_dir}  ({dcs_files} .dcs files)")
    print(f"entries            : {total}" + (f"  (min {args.min_entries})" if args.min_entries else ""))
    print(f"reviewed           : {percent:.2f}% -> {int(percent)}% (README-rounded)  unreviewed {unreviewed}/{total}")
    print(f"merge locks        : {len(locked)} sources locked; {len(checked)} checked-isolated")
    print()
    print("dataset vs DCS source consistency:")
    print(f"  sources in dataset : {len(in_dataset)}")
    print(f"  sources in DCS     : {len(in_dcs)}")
    print(f"  in dataset not DCS : {len(only_dataset)}")
    print(f"  in DCS not dataset : {len(only_dcs)}")
    for source in list(only_dataset)[:args.sample]:
        print(f"    dataset-only: {source}")
    for source in list(only_dcs)[:args.sample]:
        print(f"    dcs-only    : {source}")
    print()

    # Per-provider coverage, largest first, so a missing/thin provider is obvious at a glance.
    counts = {}
    for entry in data:
        for source in entry.get("sources", []):
            host = host_of(source)
            if host not in EXCLUDED_HOSTS:
                counts[host] = counts.get(host, 0) + 1
    print("provider coverage:")
    for host, count in sorted(counts.items(), key=lambda kv: kv[1], reverse=True):
        print(f"  {count:8d}  {host}")
    print()

    problems = []
    if not consistent:
        problems.append("dataset and DCS sources differ")
    if not entries_ok:
        problems.append(f"entries {total} below --min-entries {args.min_entries}")

    if problems:
        print("RESULT: FAIL - " + "; ".join(problems))
        return 1
    print("RESULT: PASS - dataset is consistent with the DCS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
