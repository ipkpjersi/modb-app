#!/usr/bin/env python3
"""
Rewrite the week for the next download in the DCS files of a single metadata provider.

Why this exists
---------------
DCS entries are only updated for anime which were downloaded in the current run. While a metadata
provider is listed in 'deactivatedMetaDataProviders' no crawler runs for it, so its DCS entries keep
the schedule of the last run in which it was still active and their week for the next download passes.
That is expected and DownloadControlStateWeeksValidationPostProcessor skips deactivated provider.

This script is the manual override for the cases where the schedule itself has to be moved:

  * Reactivating a provider that was off for a while. Every one of its entries is overdue, so the
    first run downloads all of them at once. Spreading the entries over a few weeks turns that into
    several smaller runs.
  * Pushing entries out of the way without deactivating the provider.

Only "_nextDownload" is rewritten. "_lastDownloaded" is left untouched, because it is a record of what
actually happened and must stay that way.

Usage
-----
  # what would change (default, writes nothing)
  ./reschedule-dcs.py --hostname anisearch.com --in-weeks 4

  # apply it
  ./reschedule-dcs.py --hostname anisearch.com --in-weeks 4 --apply

  # distribute the entries randomly over the next 1 to 6 weeks instead of a single week
  ./reschedule-dcs.py --hostname anisearch.com --spread 1-6 --apply

The DCS directory is taken from 'downloadControlStateDirectory' in the config.toml of the run
directory (MODB_RUN_DIR, default ~/modb-run) unless --dcs-dir is given.
"""

import argparse
import datetime
import os
import pathlib
import random
import re
import sys

# Matches the "_nextDownload" object of a DCS file. Only the two numbers are rewritten, so the rest of
# the file, including the anime payload, is byte for byte the same afterwards.
NEXT_DOWNLOAD_PATTERN = re.compile(
    r'("_nextDownload"\s*:\s*\{\s*"year"\s*:\s*)(\d+)(\s*,\s*"week"\s*:\s*)(\d+)(\s*})'
)

DCS_DIRECTORY_PATTERN = re.compile(r'^\s*downloadControlStateDirectory\s*=\s*"([^"]+)"', re.MULTILINE)


def week_of_year(weeks_from_now):
    """ISO year and week of the date which is the given number of weeks from today."""
    target = datetime.date.today() + datetime.timedelta(weeks=weeks_from_now)
    iso = target.isocalendar()
    return iso[0], iso[1]


def dcs_directory_from_config():
    run_dir = pathlib.Path(os.environ.get("MODB_RUN_DIR", pathlib.Path.home() / "modb-run"))
    config = run_dir / "config.toml"

    if not config.is_file():
        sys.exit(f"error: no config.toml in {run_dir} (set MODB_RUN_DIR or pass --dcs-dir)")

    match = DCS_DIRECTORY_PATTERN.search(config.read_text(encoding="utf-8"))

    if not match:
        sys.exit(f"error: no 'downloadControlStateDirectory' in {config} (pass --dcs-dir)")

    return pathlib.Path(match.group(1))


def parse_spread(value):
    match = re.fullmatch(r"(\d+)-(\d+)", value)

    if not match:
        raise argparse.ArgumentTypeError("spread must look like MIN-MAX, for example 1-6")

    minimum, maximum = int(match.group(1)), int(match.group(2))

    if minimum < 1 or maximum < minimum:
        raise argparse.ArgumentTypeError("spread must satisfy 1 <= MIN <= MAX")

    return minimum, maximum


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--hostname", required=True, help="hostname of the metadata provider, for example anisearch.com")
    parser.add_argument("--dcs-dir", type=pathlib.Path, help="root DCS directory (default: taken from config.toml)")
    parser.add_argument("--apply", action="store_true", help="write the changes (default is a dry run)")
    parser.add_argument("--all", action="store_true", help="also reschedule entries whose next download is still in the future")
    parser.add_argument("--seed", type=int, help="seed for --spread, to make a dry run and the following apply pick the same weeks")

    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--in-weeks", type=int, metavar="N", help="schedule every entry N weeks from now")
    target.add_argument("--spread", type=parse_spread, metavar="MIN-MAX", help="schedule each entry a random number of weeks from now, within MIN-MAX")

    args = parser.parse_args()

    if args.in_weeks is not None and args.in_weeks < 1:
        sys.exit("error: --in-weeks must be at least 1, otherwise the next download would not be in the future")

    dcs_dir = args.dcs_dir if args.dcs_dir else dcs_directory_from_config()
    provider_dir = dcs_dir / args.hostname

    if not provider_dir.is_dir():
        sys.exit(f"error: no DCS directory for [{args.hostname}]: {provider_dir}")

    random.seed(args.seed)

    current_year, current_week = week_of_year(0)
    files = sorted(provider_dir.glob("*.dcs"))

    if not files:
        sys.exit(f"error: no *.dcs files in {provider_dir}")

    rescheduled = 0
    skipped = 0
    weeks_written = {}

    for file in files:
        content = file.read_text(encoding="utf-8")
        match = NEXT_DOWNLOAD_PATTERN.search(content)

        if not match:
            sys.exit(f"error: no '_nextDownload' found in {file}")

        year, week = int(match.group(2)), int(match.group(4))
        is_overdue = (year, week) <= (current_year, current_week)

        if not is_overdue and not args.all:
            skipped += 1
            continue

        weeks_from_now = args.in_weeks if args.in_weeks is not None else random.randint(*args.spread)
        new_year, new_week = week_of_year(weeks_from_now)

        updated = NEXT_DOWNLOAD_PATTERN.sub(
            lambda m: f"{m.group(1)}{new_year}{m.group(3)}{new_week}{m.group(5)}",
            content,
            count=1,
        )

        if args.apply:
            # Write to a temporary file in the same directory and move it into place, so an interrupted
            # run cannot leave a half written DCS file behind.
            temporary = file.with_suffix(file.suffix + ".tmp")
            temporary.write_text(updated, encoding="utf-8")
            os.replace(temporary, file)

        rescheduled += 1
        weeks_written[(new_year, new_week)] = weeks_written.get((new_year, new_week), 0) + 1

    mode = "rescheduled" if args.apply else "would reschedule (dry run, nothing written)"
    print(f"[{args.hostname}] current week: {current_year}-W{current_week:02d}, {len(files)} DCS entries")
    print(f"[{args.hostname}] {mode}: {rescheduled}, left alone: {skipped}")

    for (year, week), count in sorted(weeks_written.items()):
        print(f"    next download {year}-W{week:02d}: {count}")

    if rescheduled and not args.apply:
        print("\nre-run with --apply to write the changes")


if __name__ == "__main__":
    main()
