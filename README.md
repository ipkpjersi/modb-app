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
