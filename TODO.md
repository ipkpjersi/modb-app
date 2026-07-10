# TODO / Backlog

Tracked work for this fork. Ordered roughly by priority; items 1 and 2 are related
(residential routing needs per-provider selection to be useful).

## 1. Config-based scraper (provider) selection

**Problem.** Which providers run is currently hardcoded in `app/.../App.kt` as a block of
`launch { <Provider>Crawler...start() }` calls, with anidb and anime-planet commented out.
Changing the active set means editing code + rebuilding — clumsy, error-prone, and it means
a "subset" run still requires a source change.

**Goal.** Choose which scrapers run without editing code.
- **CLI params** — e.g. `--only anidb,anime-planet` or `--skip simkl`.
- **Config file** (optional) — e.g. `config.toml` `[modb.app] enabledProviders = [...]`, or a
  per-provider `enabled` flag.
- **Precedence:** CLI overrides config; **default = ALL providers enabled** (preserves today's
  behavior — nothing changes for a normal full run).
- **Sketch:** replace the hardcoded launch block with iteration over a provider registry
  filtered by the enabled set. Keep **fail-fast** semantics (a *selected* provider failing
  still aborts the whole run — see the fail-fast decision).

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
  currently commented out in `App.kt`.
- **anisearch** is also confirmed datacenter-IP-banned (2026-07-09) and commented out. Unlike the others
  it is a direct-scrape provider (no FlareSolverr): from the server every port is TCP-refused, while from
  a residential IP the crawler's exact request (browser UA + headers) returns 200. So the tunnel will fix
  it with no other change — verified, not assumed.
- Once in place, re-enable anidb/anime-planet/simkl/anisearch (uncomment their launches / mark enabled via item 1).

**Depends on item 1** — needs per-provider routing + enable control to be practical.

## Smaller follow-ups

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
