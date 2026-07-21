#!/usr/bin/env python3
"""
List every source URL in the dataset, grouped by metadata provider, into a SOURCES.md file.

Why this exists
---------------
The generated README already reports the per-provider *counts* (the "Number of entries | Metadata
provider" table, which equals the source count per provider because each entry holds at most one source
per provider host). What the README does NOT contain is the actual list of source URLs. This script
writes that listing: a count summary followed by every URL under its provider, sorted for stable diffs.

By default it writes both the summary and the full URL listing. The full listing is large (~146k URLs,
several MB), so `--summary-only` produces just the counts table if that is all you need - though for the
counts alone the README or scripts/verify-dataset.py already suffice.

Usage
-----
  ./list-sources.py                       # write ./SOURCES.md (summary + full listing)
  ./list-sources.py -o /path/SOURCES.md   # choose the output path
  ./list-sources.py --summary-only        # counts table only
  ./list-sources.py --output-dir /path/to/anime-offline-database   # override dataset location

Paths default to config.toml in MODB_RUN_DIR (default ~/modb-run), like the other scripts. Contains NO
secrets and NO host-specific values.
"""

import argparse
import json
import os
import pathlib
import re
import sys
from collections import defaultdict
from urllib.parse import urlparse

OUTPUT_DIRECTORY_PATTERN = re.compile(r'^\s*outputDirectory\s*=\s*"([^"]+)"', re.MULTILINE)

# animecountdown.com is a derived countdown link the app adds at build time, mirroring simkl, not a
# crawled source. Kept out of the listing so the numbers match the crawled providers.
EXCLUDED_HOSTS = {"animecountdown.com"}


def host_of(url):
    netloc = urlparse(url).netloc.lower()
    return netloc[4:] if netloc.startswith("www.") else netloc


def output_dir_from_config(override):
    if override is not None:
        return pathlib.Path(override)
    run_dir = pathlib.Path(os.environ.get("MODB_RUN_DIR", pathlib.Path.home() / "modb-run"))
    config = run_dir / "config.toml"
    if not config.is_file():
        sys.exit(f"error: no config.toml in {run_dir} (set MODB_RUN_DIR or pass --output-dir)")
    match = OUTPUT_DIRECTORY_PATTERN.search(config.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"error: no 'outputDirectory' in {config} (pass --output-dir)")
    return pathlib.Path(match.group(1))


def main():
    parser = argparse.ArgumentParser(description="List all dataset source URLs grouped by provider into SOURCES.md.")
    parser.add_argument("-o", "--output", default="SOURCES.md", help="output file (default: ./SOURCES.md)")
    parser.add_argument("--output-dir", help="dataset directory (default: outputDirectory from config.toml)")
    parser.add_argument("--dataset-file", default="anime-offline-database-minified.json",
                        help="dataset file name inside the output directory (default: %(default)s)")
    parser.add_argument("--summary-only", action="store_true", help="write only the per-provider counts table")
    args = parser.parse_args()

    dataset_file = output_dir_from_config(args.output_dir) / args.dataset_file
    if not dataset_file.is_file():
        sys.exit(f"error: dataset file not found: {dataset_file}")

    doc = json.loads(dataset_file.read_text(encoding="utf-8"))
    data = doc["data"]

    by_provider = defaultdict(list)
    for entry in data:
        for source in entry.get("sources", []):
            host = host_of(source)
            if host not in EXCLUDED_HOSTS:
                by_provider[host].append(source)

    total = sum(len(v) for v in by_provider.values())
    # Largest provider first, matching the README table's ordering.
    ordered = sorted(by_provider.items(), key=lambda kv: len(kv[1]), reverse=True)

    lines = []
    lines.append("# Sources")
    lines.append("")
    lines.append(f"Generated from `{dataset_file.name}` (`lastUpdate` {doc.get('lastUpdate', 'unknown')}). "
                 f"**{total}** sources across **{len(ordered)}** providers, **{len(data)}** dataset entries.")
    lines.append("")
    lines.append("| Number of sources | Metadata provider |")
    lines.append("|-------------------|-------------------|")
    for host, urls in ordered:
        lines.append(f"| {len(urls)} | [{host}](https://{host}) |")
    lines.append("")

    if not args.summary_only:
        for host, urls in ordered:
            lines.append(f"## {host} ({len(urls)})")
            lines.append("")
            for url in sorted(urls):
                lines.append(url)
            lines.append("")

    output_path = pathlib.Path(args.output)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    size_mb = output_path.stat().st_size / 1_000_000
    mode = "summary only" if args.summary_only else "summary + full URL listing"
    print(f"wrote {output_path} ({mode}, {total} sources, {size_mb:.2f} MB)")


if __name__ == "__main__":
    sys.exit(main())
