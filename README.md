[![Tests](https://github.com/ipkpjersi/modb-app/actions/workflows/tests.yml/badge.svg)](https://github.com/ipkpjersi/modb-app/actions/workflows/tests.yml) [![codecov](https://codecov.io/gh/ipkpjersi/modb-app/graph/badge.svg?token=66LR8JA8KE)](https://codecov.io/gh/ipkpjersi/modb-app) ![jdk25](https://img.shields.io/badge/jdk-25-informational)
# modb-app

_[modb](https://github.com/ipkpjersi?tab=repositories&q=modb&type=source)_ stands for _**M**anami **O**ffline **D**ata**B**ase_. The applications and libraries of this repository are used to create the [ipkpjersi/anime-offline-database](https://github.com/ipkpjersi/anime-offline-database). Don't use these libraries and applications to crawl the websites entirely. Instead, check whether the dataset already offers the data that you need.

> [!NOTE]
> This project was originally created by manami-project. After the project was archived, it is now maintained by ipkpjersi to keep the project alive.

* **analyzer:** Allows to review the entries of the dataset and create merge locks.
* **anidb:** Config, downloader and converter for [anidb.net](https://anidb.net)
* **anilist:** Config, downloader and converter for [anilist.co](https://anilist.co)
* **anime-planet:** Config, downloader and converter for [anime-planet.com](https://anime-planet.com)
* **animenewsnetwork:** Config, downloader and converter for [animenewsnetwork.com](https://animenewsnetwork.com)
* **anisearch:** Config, downloader and converter for [anisearch.com](https://anisearch.com)
* **app:** The application that runs the crawlers, merges anime and updates the repository.
* **core:** Core functionality used by all other modules.
* **kitsu:** Config, downloader and converter for [kitsu.app](https://kitsu.app)
* **lib:** A library that drives the applications "app" and "analyzer".
* **livechart:** Config, downloader and converter for [livechart.me](https://livechart.me)
* **myanimelist:** Config, downloader and converter for [myanimelist.net](https://myanimelist.net)
* **serde:** Serialization and deserialization of the finalized dataset files.
* **simkl:** Config, downloader and converter for [simkl.com](https://simkl.com/anime/) as well as config for [animecountdown.com](https://animecountdown.com).
* **test:** All essential dependencies as well as some convenience functions and classes for creating tests.

## Documentation

* General
  * [Data quality](docs/data-quality.md)
* Downloading
  * [Download Control State (DCS)](docs/dcs.md)
  * [Data lifecycle](docs/data-lifecycle.md)
* Merging
  * [Merging](docs/merging.md)
  * [Merge locks](docs/merge-locks.md) 
  * [Reviewed isolated entries](docs/reviewed-isolated-entries.md)
* Terminology
  * [Terminology](docs/terminology.md)

## Requirements

* JDK/JVM 25 (LTS) or higher
* Linux/Unix system supporting
  * `make`
  * `bash`
  * `set`
  * `echo`
  * `rm`
  * `jsonschema` (https://github.com/sourcemeta/jsonschema)
  * `gh`
  * `git`
  * `ifconfig`
* ipv6 based internet connection with SLAAC enabled

## Getting started

Setup is identical for app and analyzer.
* Clone `https://github.com/ipkpjersi/anime-offline-database`
  * Run `make check-requirements` in that directory to see if you've got all requirements installed
  * Run `make init-or-reset` in that directory
* Create a separate directory for the `*.jar` files and place the [latest releases](https://github.com/ipkpjersi/modb-app/releases) in that directory
* Create a third directory for DCS files
* Create a fourth directory for raw download files
* Create a [configuration file](core/README.md#configuration-management).
  * Set all the properties from the "Configuration" section down below which don't offer a default value.
  * Provider credentials also live here, e.g. the MyAnimeList API client id is the
    `modb.myanimelist.clientId` property (sent as the `X-MAL-CLIENT-ID` header). Keep `config.toml`
    out of version control - it is already covered by `.gitignore`.

### Optional: Logback configuration

Optionally you can create a [logback configuration](https://logback.qos.ch/manual/configuration.html) to override the default setup.

### Start using IDE

Run `main()` in `io/github/manamiproject/modb/app/App.kt` of the `app` module or `io/github/manamiproject/modb/analyzer/Analyzer.kt` of the `analyzer` module with the following VM parameter:
* `-Djava.net.preferIPv6Addresses=true`
* `-Djava.net.preferIPv4Stack=false`

### Start using *.jar file

Run
* either `java -Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false -jar modb-app.jar`
* or `java -Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false -jar modb-analyzer.jar`

### Optional: crash alerts (Discord webhook)

`scripts/run-app.sh` launches the app and, on a hard crash (the JVM exiting non-zero when it was not a
manual Ctrl+C), posts the tail of the run output to a Discord webhook. The webhook URL is read from
the `MODB_DISCORD_WEBHOOK` environment variable, or from a `discord-webhook.txt` file in the run
directory (alongside `modb-app.jar` and `config.toml`). Put just the raw URL on a single line, with
no quotes:

```
https://discord.com/api/webhooks/<id>/<token>
```

Keep that file out of version control - it is already covered by `.gitignore`. If no webhook is
configured the alert is simply skipped.

## Configuration

For more configuration options see the `README.md` files of the respective modules.

| parameter                                | type     | default                                                                     | description                                                                                                                                                                                               |
|------------------------------------------|----------|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `modb.app.downloadsDirectory`            | `String` | -                                                                           | Root directory in which the raw files and converted files are stored.                                                                                                                                     |
| `modb.app.outputDirectory`               | `String` | -                                                                           | Target output directory. Normally this should be the directory in which you cloned the [anime-offline-database](https://github.com/ipkpjersi/anime-offline-database)                                 |
| `modb.app.downloadControlStateDirectory` | `String` | -                                                                           | Root directory of download control state files.                                                                                                                                                           |
| `modb.app.logFileDirectory`              | `String` | A directory called `logs` within the working directory of the current week. | Defines the directory in which the logs saved.                                                                                                                                                            |
| `modb.app.keepDownloadDirectories`       | `Long`   | `1`                                                                         | Number of download directories to keep. Download directories contain both raw data and conv files (intermediate format). Default is `1` which means that only the most recent download directory is kept. |

## IP rotation

Some sites throttle or block individual source addresses, so the app can rotate its outbound IPv6 source address (via `LinuxNetworkController`, using the `modb.app.networkInterface` and `modb.app.ipv6Prefix` configuration) and retry. Rotation is triggered **only** by connection-level failures — `ConnectException`, `UnknownHostException` and `NoRouteToHostException`. HTTP-level bans (e.g. `403`/`429`) are retried on the same address and do **not** rotate.

Rotation is wired into a subset of scrapers only:

| scraper       | triggers IP rotation |
|---------------|----------------------|
| anisearch     | yes                  |
| anidb         | yes (see note)       |
| anilist       | no                   |
| kitsu         | no                   |
| myanimelist   | no                   |
| livechart     | no                   |
| animenewsnetwork | no                |
| simkl         | no                   |
| anime-planet  | no                   |

When rotation happens it is logged at `INFO`:

```
IPv6 address rotation has been triggered.
Rotating outbound IPv6 source address to [<address>].
```

> **Note on anidb:** rotation must be removed from anidb once it is routed through the residential reverse SSH tunnel. On the tunnel its traffic exits via a fixed home IP that cannot (and should not) be rotated, and `restartAsync()` rotates the *shared* datacenter interface — so rotating on anidb's behalf would disrupt anisearch and the other providers that still exit via the datacenter IP. anisearch stays on the datacenter IP and keeps rotation.

## Merging, merge locks and the "reviewed" percentage

Each entry in the dataset represents a single anime whose `sources` array links that anime across the (up
to nine) metadata providers. Combining the per-provider entries into one is **merging**, and the
`_(N% reviewed)_` figure in the dataset's own README reflects how much of that merging has been
**human-confirmed**. This trips people up, so to be precise: **"reviewed" is not about reviewing each
anime's data — it is about confirming which provider IDs refer to the same anime.** You never have to
review entries to produce a release; `0% reviewed` is a fully valid, releasable dataset.

### How merging works (automatic)

`DefaultMergingService` builds **golden records** — the canonical merged entry that accumulates `sources`
from multiple providers. Providers are processed largest-first (myanimelist seeds the golden records), then
the rest in descending order of entry count. For each incoming anime:

1. If a **merge lock** already covers its sources, it is force-merged into that golden record and the
   automatic matching is skipped.
2. Otherwise candidate golden records are looked up by **title**, a matching probability is calculated for
   each, and if the best is **≥ 80%** the anime is merged. Below 80% it is deferred and retried over up to
   **4** run-throughs (a golden record can gain data from other merges that pushes a later match over the
   threshold). If it never reaches 80%, it becomes its **own** golden record — i.e. a separate entry.

So even with **no** review at all, the 80% matcher merges everything it can identify by title/probability.
Review is a **confirmation layer on top of** merging, not the thing that performs it.

### What "reviewed" means

An entry counts as reviewed once its grouping decision is human-confirmed. That confirmation is recorded in
two files inside the download-control-state directory (`modb.app.downloadControlStateDirectory`):

| file | format | meaning |
|------|--------|---------|
| `merge.lock` | JSON `{"mergeLocks":[[uri, uri, …], …]}` | Each group is a set of source URLs confirmed to be the **same** anime. A locked group is force-merged on every future run, overriding the 80% heuristic. A given source may appear in only one group. |
| `checked-isolated-entries.txt` | plain text, one source URL per line | Each URL is confirmed to be a **standalone** anime that should **not** merge with anything. |

The percentage (`DefaultReadmeCreator`) is computed per entry, ignoring `animecountdown` (which mirrors
`simkl`):

* a **multi-source** entry is reviewed if all of its sources are in one `merge.lock` group;
* a **single-source** entry is reviewed if it is listed in `checked-isolated-entries.txt` (or a merge lock).

`reviewed% = 100 − (unreviewed / total × 100)`.

### Recording reviews

Merge locks and reviewed-isolated entries are written by the **modb-analyzer** tool (`Analyzer.kt`), not
during a normal crawl. Reviewing is **incremental and persistent**: both files are read and reused by every
subsequent run, so a confirmed decision is never redone — only newly ambiguous entries ever need attention.
The scary jump from `0%` toward a high percentage is a one-time backlog, not a recurring cost.

### Starting from 0% (forks)

The upstream maintainer's accumulated review data (their `merge.lock` / `checked-isolated-entries.txt`) is
private and is **not** distributed with the dataset, so a fork starts at **0% reviewed** — auto-merge only.
The practical consequences are:

* mostly **duplicate** entries — the same anime left as two golden records because the title match stayed
  under 80%. These are harmless to the schema and can be merged either by review or in the consuming
  application.
* rarely an **over-merge** — two genuinely different anime combined into one entry (from a bad cross-provider
  link). This can only be undone by review (a *split*); a downstream "merge" cannot fix it.

Note also that a fork's higher raw entry count vs. upstream is a **mix** of genuinely new anime and unmerged
duplicates — it is not one-for-one duplicates.

### Bootstrapping review data from a public release

Upstream's raw `merge.lock` is private, but its **published dataset already encodes the identical groupings**:
every entry's `sources` array is exactly a merge-lock group (a set of source URLs confirmed to be the same
anime). `scripts/bootstrap-merge-locks.py` reconstructs `merge.lock` (and `checked-isolated-entries.txt`)
from any good release, so a fork can jump from 0% to most-of-the-way reviewed without hand-reviewing anything.
The initial import (2026-07-14, against the `2026-27` release) took the fork to **~89% reviewed**.

Fetch a release (for example
`https://github.com/manami-project/anime-offline-database/releases/download/2026-27/anime-offline-database-minified.json`),
then run:

```
scripts/bootstrap-merge-locks.py anime-offline-database-minified.json \
  -o review-data/merge.lock \
  --fork-db ~/anime-offline-database/anime-offline-database-minified.json \
  --restrict-to-fork \
  --checked-isolated review-data/checked-isolated-entries.txt
```

| Argument | Meaning |
| --- | --- |
| `<upstream>.json` (positional) | The known-good release to reconstruct groupings from. |
| `-o, --output` | Where to write the generated `merge.lock`. |
| `--fork-db PATH` | The fork's own dataset. Required for `--restrict-to-fork` and `--checked-isolated`. |
| `--restrict-to-fork` | Keep only merge-lock sources already present in `--fork-db`, i.e. providers the fork actually crawls. **Use this.** Without it, the dead-entries validator aborts the reprocess on any source from an uncrawled provider (it has no DCS file, so it looks "dead"). |
| `--checked-isolated PATH` | Also write `checked-isolated-entries.txt`, certifying only entries that are single-source in **both** the fork and upstream (so an entry that gained a provider in the fork's newer crawl is not wrongly frozen as never-merge). |
| `--only-hosts a,b,c` | Optional explicit allowlist of provider hostnames. Redundant with `--restrict-to-fork` (which already limits to crawled providers), so normally omit it. |

The script never writes into the DCS directory itself. Review the output, keep the canonical copies in
`review-data/`, then copy both files into `<modb.app.downloadControlStateDirectory>/` and run **`[r]` Reprocess
merging** in the analyzer (or a full app run) to force-merge the fork's fragmented entries into the upstream
grouping.

Because `--restrict-to-fork` drops the uncrawled half of any cross-provider pair, entries whose only partner
is a currently-deactivated provider stay unreviewed until that provider is crawled. Reactivating those
providers and re-running the bootstrap folds them all back in at once — see TODO item 2 for the exact
crawl-first ordering, and the "Dead-entries validator" note under [Possible improvements](#possible-improvements)
for the code change that would remove the `--restrict-to-fork` requirement.

### Reviewing entries (the analyzer)

Review is done through the **analyzer**, a separate interactive terminal program — not by hand-editing
`merge.lock`. It reads the same `config.toml` as the app, so it points at the same dataset
(`modb.app.outputDirectory`) and the same DCS directory (`modb.app.downloadControlStateDirectory`). Run:

```
./scripts/run-analyzer.sh
```

(or run the jar directly from the run directory: `java -Djava.net.preferIPv6Addresses=true
-Djava.net.preferIPv4Stack=false -jar modb-analyzer.jar`). Run it inside `tmux` when you are over SSH so a
dropped connection does not interrupt a half-finished cluster.

> **Works over SSH — no desktop required.** For each entry the analyzer prints the candidate provider URLs
> as clickable **OSC 8 hyperlinks**, so you open them in your local browser straight from the terminal
> (iTerm2, Kitty, Windows Terminal, GNOME Terminal, VS Code, …; terminals without OSC 8 support just show
> the plain, copy-pasteable URLs). On a machine with a desktop it *also* tries to auto-open the pages via
> `java.awt.Desktop.browse`; on a headless server that auto-open is skipped with a warning and you use the
> printed links instead.

It loads the finished dataset into memory and shows a menu:

```
MODB - ANALYZER
------------------------------------
[1] Show unseen duplicates
[2] Show cluster sizes
[3] Show DCS statistics
------------------------------------
[c] Check merge locks
[d] Mark as dead entry
[l] Load anime manually
[n] Create a merge lock from scratch
[a] Add a URL to an existing merge lock
------------------------------------
[r] Reprocess merging
------------------------------------
[q] quit
------------------------------------

Select:
```

**The option you typically want is `[c] Check merge locks`** — that is the review loop where duplicates are
actually cleared. Everything else is a read-only report (`[1]`/`[2]`/`[3]`), a one-off manual tool
(`[n]`/`[a]`/`[d]`/`[l]`), or the apply step (`[r]`).

A **cluster** is the set of entries that have the same *number* of `sources`. The normal workflow is:

1. **`[2] Show cluster sizes`** — prints, per cluster, the total entries and how many are still
   **unreviewed**. This is your backlog. Cluster `1` (single-source entries) is where most of the
   split-anime duplicates live, but it is also the largest group, so warm up on a smaller cluster such as
   `2` to get the feel of the loop before grinding cluster `1`.
2. **`[c] Check merge locks`** — pick a cluster and it walks you through each *unreviewed* entry in it
   (entries already covered by a merge lock or `checked-isolated-entries.txt` are filtered out). For each
   one it prints the candidate URLs as clickable links (and auto-opens them in a browser if there is a
   desktop) plus a side-by-side diff table, then prompts `Option:`. Type one of:
   * **`keep`** — these sources ARE the same anime -> writes a merge lock group (force-merged forever after).
   * **a pasted provider URL** — add another provider's entry to the group you are building, then re-prompt.
   * **`check`** — (single-source entries only) confirm it is genuinely standalone -> appends it to
     `checked-isolated-entries.txt`.
   * **`skip`** — undecided; writes nothing, so it reappears next time.
   * **`exit`** — back to the main menu.

   In plain English, matching the `Provider` column of the diff table: **`keep` = "these are the same
   anime, merge/lock these providers together"**, and **`check` = "this is a unique anime, never merge it
   with other providers"**. To merge a duplicate you first paste the other provider's URL (which adds it to
   the group on screen), then type `keep` to lock the combined group. Typing `keep` on an entry you did not
   add anything to simply confirms and locks its current sources as-is.

   **The URL list it prints is not all real entries.** Only the direct URLs (with an ID, like
   `anilist.co/anime/158702`) are the entry's actual sources. The rest are pre-filled *search* links (note
   the `?q=` / `search` in them) generated for every provider the entry does **not** yet have, so you can
   quickly check whether a missing provider also has this anime. If one does, paste its real URL to add it,
   then `keep`. So a "single-source" entry just means one provider has it *so far* — the searches are how
   you look for the rest.

**Two different "duplicate" notions** — don't confuse them. They are *opposite* failure directions:

* **Split anime** (under-merged; the common ~thousands case): the same real anime exists as **two separate
  entries** because the automatic 80% title matcher never linked them. Each entry looks valid on its own
  (at most one ID per provider) — they just should have been one. This is a problem *across two rows*.
  Example:

  ```
  Entry A:  https://myanimelist.net/anime/1535   (title "Death Note")
  Entry B:  https://anilist.co/anime/1535        (title "DEATH NOTE (2006)")
  ```

  Found via `[2] Show cluster sizes` (they inflate the low-source clusters) and fixed in `[c]` by pasting
  the other URL and typing `keep`.

* **`[1] Show unseen duplicates`** (over-merged): a *different* report — one entry that contains **two or
  more IDs from the same provider**. An entry should have at most one ID per provider, so this means two
  different anime got fused into one entry (usually a bad cross-provider link). This is a problem *inside
  one row*. Example:

  ```
  One entry whose sources are:
    https://myanimelist.net/anime/1535
    https://myanimelist.net/anime/99999   <- two MAL IDs in ONE entry
    https://kitsu.app/anime/1376
  ```

  "unseen" just means "not yet covered by a merge lock". Fixing an over-merge needs a **split**, which only
  review can do — a downstream "merge" in a consuming app cannot undo it.

|                              | inside one entry or across entries? | direction     | what it means            | fix                         |
|------------------------------|-------------------------------------|---------------|--------------------------|-----------------------------|
| **Split anime**              | same anime across **two** entries   | under-merged  | not combined when it should be | `keep` to merge/lock them |
| **Unseen duplicates** `[1]`  | same provider **twice in one** entry| over-merged   | two anime fused into one | **split** (review only)     |

At 0% reviewed a fork has lots of the first kind (harmless, just redundant entries) and few of the second.

**Decisions persist; indecision does not.** `keep` and `check` write to `merge.lock` /
`checked-isolated-entries.txt` in the DCS directory and are reused by every future run of both the app and
the analyzer, so a reviewed entry never comes back. Only `skip` is transient. You therefore review each
ambiguous entry **once**, not every run.

**What a review run writes.** After finishing a cluster (or via `[r] Reprocess merging`) the analyzer
re-runs merging with your new locks and then **rewrites the dataset files and regenerates the dataset
`README.md`** — including the `N% reviewed` figure and the per-provider entry counts — in
`modb.app.outputDirectory`. This is a **delta update only**: it does *not* bump `week.release` or create a
GitHub release (that only happens on a full weekly app run).

**You do not have to review everything.** Review is an optional confirmation layer on top of the automatic
80% merger, not a requirement. The dataset is fully usable at 0% reviewed; unreviewed just means
"not yet human-confirmed", not "broken". `skip` and `exit` write nothing, decisions persist forever, and you
can stop at any point. For clearing duplicates specifically, the bounded win is the over-merges from `[1]`
(they are actually wrong); grinding the 20,949 single-source entries in cluster `1` with `check` mostly just
raises the reviewed percentage and removes no duplicates.

### Reading the read-only reports (`[1]` / `[2]` / `[3]`)

These three menu items are dashboards. They print information and change nothing.

**`[2] Show cluster sizes`** is your backlog. One row per *number of sources*, showing how many entries have
that many sources and how many are still unreviewed:

```
| Number of sources | Number of entries in database | Number of unreviewed entries |
|-------------------|-------------------------------|------------------------------|
| 1                 | 20949                         | 20949                        |
| 2                 | 7429                          | 7429                         |
| 6                 | 4796                          | 4796                         |
```

The number you type at the `[c]` prompt is this first column (the number of sources), not a cluster index. A
high source count means an anime matched across many providers, so it is well merged; the single-source row
is the large pile of possible split-anime. When the unreviewed column equals the total column on every row
you are at 0% reviewed. A count higher than the number of providers (about 9 to 10) means the entry holds
multiple IDs from one provider, so it overlaps with `[1]`.

**`[1] Show unseen duplicates`** lists the over-merges: each line is one entry that holds two or more IDs
from the *same* provider (the over-merged row in the table above). Each of these needs a split.

```
[https://anilist.co/anime/103317, https://anilist.co/anime/104064]          two AniList IDs, one entry
[https://myanimelist.net/anime/10495, https://myanimelist.net/anime/12403]  two MAL IDs, one entry
```

**`[3] Show DCS statistics`** is the crawler's redownload schedule and has nothing to do with review.
Entries are grouped by the week they are next due for download (`_nextDownload`), broken down per provider:

```
2026-29
-------
anisearch.com        : 18828

2026-30
-------
anilist.co           : 1071
myanimelist.net      : 10322
```

Read it as crawl freshness. A deactivated provider bunches all its entries in one week and stays there (all
of anisearch in a single week, because it no longer updates), while active providers spread across upcoming
weeks as a rolling schedule. A provider with no rows has zero DCS entries (it never crawled successfully).
The totals are far larger than the dataset entry count because DCS tracks each provider-source separately,
before merging (a 6-source anime is 6 DCS rows but 1 dataset entry).

## Possible improvements

Ideas that are understood but not implemented yet.

### Anilist: stop refreshing the token on repeat 403s, and back off like a 429

`AnilistHttpClient` treats a `403` as "the CSRF token is stale": it fetches a new token and retries (up to
3 attempts, with a short 5-10s / 10-20s backoff). That keeps the crawl alive, but the diagnosis behind it is
wrong, and the current settings only barely work.

Evidence from a live run: three *consecutive freshly minted* tokens were each rejected with `403` within
~170ms of being issued, and the same request then succeeded after ~20s of cumulative backoff. The token is
not the problem — only elapsed time is. The `403` is IP-level rate limiting, and the rate limit window looks
like roughly 10-20 seconds.

Two consequences:

- **The refresh makes it worse.** `AnilistDefaultTokenRetriever` fetches the token by requesting
  `anilist.co`, which is itself a request into the very rate limiter that is throttling us. Every `403`
  therefore provokes an *extra* request at exactly the wrong moment. Over one 42 minute run that was 44
  additional requests fired while being throttled.
- **The backoff is too short.** 5-10s and 10-20s barely span a 10-20s window. In that run the retry ladder
  went 36 / 7 / 1 (attempt 1 / 2 / 3) and reached the final rung once. If a rate limit window ever outlasts
  the last attempt, the `403` reaches `AnilistDownloader`, which throws `IllegalStateException: Unable to
  determine the correct case`, and the whole crawl aborts (fail-fast is intentional, see the crash alert
  section).

Suggested change:

1. Refresh the token only on the **first** `403` (cheap, and still covers a genuinely stale token). On
   repeat `403`s do not refresh at all — just back off and retry with the token already in hand. This
   removes the amplification.
2. Raise the backoff to the policy `DefaultHttpClient` already uses for `429`:
   `random(60_000, 90_000) * attempt`, i.e. ~1-1.5 min then ~2-3 min. That comfortably clears the window
   instead of racing it.

The cost is that a rate limited entry stalls for a couple of minutes instead of seconds, which is cheap
compared to aborting the crawl.

Note that IP rotation is not an option here: rotation is not wired into the anilist scraper, and rotation
only triggers on connection-level failures anyway - HTTP-level bans like `403`/`429` are always retried on
the same address (see [IP rotation](#ip-rotation)).

### Anilist: raise the per-entry delay

`AnilistCrawler` downloads strictly sequentially (a `repeat(...)` loop, one request in flight at a time) and
waits `random(1000, 2000)` ms between entries. Since the `403`s above are rate limiting, the cheapest fix
may not be recovery at all but prevention: raising that delay reduces the `403`s at the source. `anisearch`
was slowed down (`random(3000, 6000)` ms) for the same reason.

### Dead-entries validator: skip deactivated providers in the merge-lock check

`DeadEntriesValidationPostProcessor.process()` runs three dead-entries checks, but they are not symmetric.
The DCS check and the dataset check both `filterNot { ignoreMetaDataConfiguration.any { it.hostname() ==
source.host } }` before validating, so sources from non-crawlable providers are dropped first. The merge-lock
check does not - it validates `mergeLockAccess.allSourcesInAllMergeLockEntries()` unfiltered.

That asymmetry is only harmless as long as `merge.lock` never references a provider the fork is not crawling.
It does not matter for the app's own merge locks (those only ever contain crawled sources), but it is exactly
what forces `scripts/bootstrap-merge-locks.py` to run with `--restrict-to-fork`: the bootstrap imports
upstream's groups, which legitimately reference deactivated providers (anidb / anime-planet / simkl /
anisearch). Any such URL has no DCS file, so `determineDeadEntries` treats it as dead and
`check(deadEntriesInList.isEmpty())` aborts the whole reprocess (this was the second bootstrap crash).

`--restrict-to-fork` sidesteps it by stripping every uncrawled source before writing `merge.lock`, but that
has a real cost: an upstream group like `[anime-planet, kitsu]` collapses to a single `kitsu` source, drops
below the two-source threshold, and is skipped entirely. The `kitsu` entry then shows up in the analyzer as
an unreviewed single-source (cluster 1) entry even though upstream already grouped it - about 3,745 of the
~3,846 cluster-1 stragglers are these deferred cross-provider splits, not genuinely new anime.

Suggested change: give the merge-lock check the same filter the other two already have - drop sources whose
host is a deactivated (or otherwise non-crawlable) provider before calling `determineDeadEntries`. Then the
bootstrap can run **without** `--restrict-to-fork` and keep the full upstream groups. Those ~3,745 entries
would count as reviewed immediately (their source is part of a valid lock), and each would reunite with its
partner automatically the moment that provider is crawled - no bootstrap re-run needed. The deactivated
sources in the lock stay inert until then, doing no harm because the merger only force-merges the sources
that actually exist. Worth confirming nothing else in the pipeline trips on a lock source that has no DCS
file before dropping the flag, but the validator is the only known blocker.
