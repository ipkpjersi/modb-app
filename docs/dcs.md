# Download Control State (DCS)

+ Anime are not downloaded once. They are checked for updates regularly
+ Updates for [anime-offline-database](https://github.com/ipkpjersi/anime-offline-database) are designed to be created per week
+ Requirement: Creating a weekly update must be possible within a day
+ Ongoing and upcoming anime have a higher tendency to change and therefore will be updated every week
+ Changes for finished anime are less likely
+ Downloading anime with frequent or recent changes more often and other anime less often reduces the number of downloads per week
+ This also reduces load on the metadata providers
+ Every anime is updated at least once per quarter

## What is DCS and how does it work?

Download Control State (DCS) is a metadata provider specific tracker for each anime.
It basically tracks each anime on each metadata provider over time. Based on the changes it orchestrates which
anime that are already known in the dataset need to be downloaded again for updates. It's important to know that
anime are not downloaded only once. They are re-downloaded and checked for updates regularly.

Despite the name, a DCS file is not only a scheduler. Each DCS file also stores the anime's converted metadata
from that provider (title, sources, episodes, tags, scores and so on) next to the scheduling fields. So the DCS
files are the app's actual data store. The weekly `downloads` directory is transient staging that is pruned after
the retention period, while the accumulated DCS set is the source of truth. The dataset files are a stateless,
regenerated view of it: many per-provider DCS records are merged into one entry via the merge locks (see
[Data lifecycle](data-lifecycle.md) and [Merging](merging.md)).

In the beginning it was possible to run the application once a week. It was possible to download all anime within a day.
An average week contained a low two-digit number of new anime at max.
When MAL experienced consecutive weeks of updates with hundreds of new anime, scaling became an issue faster than
expected. The requirement was always to be able to start and finish the process within a day.
DCS has been introduced to be able to scale and meet that requirement. It tracks each anime entry on each metadata
provider individually for changes. It's only responsible for anime which are already known in the dataset and schedules
when to download them again.
Anime which are ongoing or upcoming are expected to change frequently. Therefore, they are updated every week until they
finished. If they have finished it depends on the frequency of changes when they will be downloaded again.
If an anime is neither ongoing nor upcoming and has no changes for the first time, it is scheduled for the next download
somewhere between 2-4 weeks from now. The exact number of weeks is picked randomly to further distribute the number of
downloads. If an anime, which is neither ongoing nor upcoming, hasn't changed repeatedly, it will be downloaded
in the number of weeks without changes.

Let's assume that with this week's update an anime had no changes for the past four weeks, then it will be downloaded
again in four weeks. This way the span between downloads for each anime will increase and reduces the number of anime to
download. However, there is a limited to this. The maximum number of weeks this can add up to is 12 weeks. The reason is
that every anime must be downloaded from each metadata provider at least once per quarter.

**Example lifecycle:**

* **2019-01** Newly added as `UPCOMING`
* **...** Updated every week
* **2019-14** Updated to `ONGOING`
* **...** Updated every week
* **2019-28** Updated to `FINISHED`
* **2019-29** First week without changes. Redownload in `3` weeks
* **2019-32** No changes. Redownload in `6` weeks
* **2019-38** No changes. Redownload in `12` weeks
* **2019-50** No changes. Redownload in `12` weeks
* **2020-09** Change detected. Redownload next week.
* **2020-10** First week without changes. Redownload in `2` weeks
* **...**

## Deactivated metadata provider

A metadata provider can be deactivated by adding its hostname to `deactivatedMetaDataProviders` in `config.toml`.
No crawler is started for it, which has a consequence for DCS that is easy to miss.

DCS entries are only updated for anime which have been downloaded in the current run. A deactivated metadata provider
downloads nothing, so its DCS entries keep the schedule of the last run in which it was still active. The week for
their next download therefore arrives and then passes, while nothing updates them. Its anime remain in the dataset,
they are simply no longer refreshed.

For that reason `DownloadControlStateWeeksValidationPostProcessor` skips the entries of deactivated metadata provider.
Without that exception every run would fail once the week for the next download of the first stale entry has been
reached, and the failure would surface at the very end of the run, long after the crawlers finished.

Note that deactivating a metadata provider stops the guarantee that every anime is downloaded from each metadata
provider at least once per quarter. The data of a deactivated metadata provider ages for as long as it stays off.
