# TODO / Backlog

Tracked work for this fork. Ordered roughly by priority; items 1 and 2 are related
(residential routing needs per-provider selection to be useful).

## 1. Config-based scraper (provider) selection

**Mostly done (2026-07-14).** `config.toml` `[modb.app] deactivatedMetaDataProviders = [...]` now lists the
hostnames to skip. `App.kt` iterates a provider registry filtered by that set instead of the old block of
commented-out `launch { <Provider>Crawler...start() }` calls, crawlers for deactivated providers are never
instantiated, and fail-fast is unchanged (a *selected* provider failing still aborts the whole run).
Default = ALL providers enabled (empty list), so a normal full run behaves as before.

**CLI params done (2026-07-14).** `main` now accepts `--only anidb,anime-planet` / `--skip simkl` (both take a
comma-separated list; a provider is named by hostname `anidb.net` or short label `anidb`, and `--only=x` also
works). `--only` is an allowlist that deactivates every other provider for the run and can even re-enable a
provider that `config.toml` deactivated (the residential-only test-run case); `--skip` is additive on top of the
config list. The two are mutually exclusive and a bad flag/unknown provider fails fast with usage before the sudo
prompt. `MetaDataProviderSelection` (parser) resolves the effective deactivated set and `SelectedProvidersConfig`
(a `Config` decorator) feeds it to both consumers - the crawler filter and
`DownloadControlStateWeeksValidationPostProcessor` - so they stay consistent. No CLI flag = config.toml behavior
unchanged.

**Payoff.** Enables a *targeted live run* of just anidb/anime-planet to confirm they actually
work (or get blocked) end-to-end. `check-all-providers.sh` is only a single-request reachability
probe — it does **not** prove a full crawl survives, so this is the real test vehicle.

## 2. Reverse SSH tunnel for residential-only providers

**Problem.** anidb and anime-planet ban datacenter IP ranges outright. IPv6 rotation only shuffles
addresses within the Hetzner /64 (all still datacenter IPs), so it can't help; FlareSolverr solves
their Cloudflare JS challenge but not the datacenter-range ban. They need a **residential exit IP**.

**Goal.** Route selected providers' outbound traffic (both direct HTTP *and* their FlareSolverr
fetches) through a reverse SSH tunnel to a residential connection.
- **Provider-scoped:** only residential-required providers use the tunnel; everyone else keeps the
  direct datacenter path.
- **simkl** is now confirmed to need the tunnel too (datacenter IP banned even via FlareSolverr) and is
  currently listed in `deactivatedMetaDataProviders`.
- **anisearch** is also confirmed datacenter-IP-banned (2026-07-09) and deactivated. Unlike the others
  it is a direct-scrape provider (no FlareSolverr): from the server every port is TCP-refused, while from
  a residential IP the crawler's exact request (browser UA + headers) returns 200. So the tunnel will fix
  it with no other change — verified, not assumed.
- Once in place, re-enable anidb/anime-planet/simkl/anisearch by removing their hostnames from
  `deactivatedMetaDataProviders` in `config.toml`. No rebuild needed.

### Handle the stale DCS entries when reactivating

anisearch is the only deactivated provider with DCS history: **18,828 entries**, last downloaded in
`2026-W28`, all with `_nextDownload` set to `2026-W29`, which passed while it was off. (anidb,
anime-planet and simkl have zero DCS entries — they were never crawled successfully, so they have no
schedule to go stale and nothing to do here.)

Nothing is broken by this — `DownloadControlStateWeeksValidationPostProcessor` skips deactivated
providers — but it does mean **every anisearch entry is overdue**, so the first run after reactivation
re-downloads all ~18.8k of them in one go. That is the same volume it already did in a normal week
(`2026-28` downloaded every anisearch entry), so it should be fine, just a long run.

If that first run needs to be smaller, spread the schedule out beforehand with:

    scripts/reschedule-dcs.py --hostname anisearch.com --spread 1-6 --apply

That rewrites only `_nextDownload` (never `_lastDownloaded`) and turns the single catch-up run into
several smaller ones. The trade-off is that entries pushed further out keep serving their `2026-W28`
data until their week arrives, so only do it if the one-shot run is actually a problem.

**Depends on item 1** — needs per-provider routing (enable control is done).

## Smaller follow-ups

- **Make `DefaultDownloadControlStateUpdater.updateAll()` safe to run twice in the same week.** Its own
  class doc says it "can only be executed once in a meaningful way per weekly update", and that is a real
  trap: if a run crashes *after* `updateAll()` (as it did on 2026-07-13, in
  `DownloadControlStateWeeksValidationPostProcessor`), the only way to finish the week is to re-run the
  app, which runs `updateAll()` a second time over the same `*.conv` files. Every finished anime then
  compares equal to itself, takes the `scheduleRedownloadForUnchangedAnime()` branch, and gets
  `_weeksWihoutChange` inflated and `_nextDownload` pushed out an extra backoff step (~85k entries on the
  2026-W29 re-run). Bounded and self-healing (the 12-week cap still holds) and ongoing/upcoming anime are
  unaffected (they always reschedule to +1 week), but it degrades refresh frequency for a cycle and the
  cost is paid again on every late-stage crash.
  **Fix:** in `handleUpdate`, skip the entry when the existing DCS entry has `_lastDownloaded ==
  WeekOfYear.currentWeek()` *and* its stored anime equals the conv anime (same comparison `update()` uses,
  i.e. ignoring scores). That entry was already fully accounted for this week, so there is nothing to do.
  A *partially* updated crash still heals correctly, because entries that never got updated still carry an
  older `_lastDownloaded`, and an entry genuinely re-downloaded with new data still differs and takes the
  normal update path. Needs tests for all three cases.

- **Bring MAL tests up to parity with the other providers.** `MyanimelistAnimeConverter` (the
  heaviest-logic class) has only 4 `@Nested` test groups (HappyPath, Defaults, Type, Status) versus
  the ~17 per-field groups the sibling converters have (e.g. kitsu). Add dedicated groups for the
  currently untested `extract*` fields: Title, Episodes, PictureAndThumbnail, Duration, Synonyms,
  Sources, RelatedAnime, Tags, Studios, Scores, AnimeSeason. Also add a test pinning `producers` as
  always-empty under the v2 API (it does not expose producers). This matters because the v2 API
  migration is the newest, most-changed code in the tree and deliberately drops fields, so it is
  exactly where field-level regression coverage is most valuable and currently thinnest. Mirror the
  kitsu converter test structure and reuse the existing MAL test fixtures.
- **Latent core bug** (spotted during the FlareSolverr work, not yet fixed): `PathExtensions.readFile`
  throws `NoSuchFileException(this.toString())` inside `withContext`, so the exception message is the
  coroutine scope (e.g. `DispatchedCoroutine{Active}@...`), not the missing file path — misleading when
  debugging. Fix separately.
- **Harden `scripts/check-all-providers.sh`:** add a provider-name filter (run just a subset),
  a `FS_PORT` override so its FlareSolverr can't contend with a live crawl's single browser
  (avoids a 500 that would fail-fast the running crawl), and bind its container to `127.0.0.1`
  instead of `0.0.0.0` (same open-proxy exposure fixed in the app).
