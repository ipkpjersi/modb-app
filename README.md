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

`scripts/run.sh` launches the app and, on a hard crash (the JVM exiting non-zero when it was not a
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
