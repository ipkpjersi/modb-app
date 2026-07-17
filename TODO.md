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

**DONE (2026-07-17).** All four residential-only provider(s) are crawling again:
`deactivatedMetaDataProviders` is now empty, `modb.app.tunnel.enabled = true`, `danted` + `autossh` run on
the home machine, and `scripts/tunnel/check-tunnel.sh` passes all four checks. Verified against the live
services rather than assumed:

| Provider | direct (datacenter) | via tunnel (residential) |
| --- | --- | --- |
| anidb.net | FlareSolverr: *"Cloudflare has blocked this request. Probably your IP is banned for this site"* | `200`, "Challenge solved!" |
| anime-planet.com | 403 | `200`, "Challenge not detected!" |
| simkl.com | 403 | `200`, "Challenge not detected!" |
| anisearch.com | TCP refused (curl exit 7) | `301` |

Cloudflare naming the IP ban outright, and anime-planet/simkl not even being challenged from a residential
IP, confirms the block was purely IP-based exactly as diagnosed.

**How it works.**
- **App side.** `TunnelConfig` (`modb.app.tunnel.*`) selects which provider(s) route through the SOCKS5
  tunnel; `enabled` defaults to `false`, so a host without a tunnel is unaffected. Direct-scrape provider(s)
  use `tunnelAwareHttpClient(...)`, which keeps the `SuspendableHttpClient` wrapper (IPv6-rotation waits and
  the retry cases still apply) and only swaps the inner `DefaultHttpClient` for a proxied one - anisearch's
  three client sites use it. FlareSolverr provider(s) are proxied by FlareSolverr itself: `proxyProperty()`
  appends `"proxy":{"url":"socks5://..."}` to the payload, keyed off the target URL's host, because it is
  FlareSolverr's container - not this process - that makes the outbound request. `checkTunnel()` runs in
  `App.kt` before the sudo prompt and aborts if the tunnel is down while a tunneled provider is active (it
  no-ops when none is, e.g. `--only anilist`). 21 tests.
- **Ops.** `scripts/tunnel/`: `sockd.conf`, `modb-tunnel.service`, `check-tunnel.sh`, `README.md`.
- **The server cannot reach the home machine.** Home dials out (`ssh -R`), so it opens no inbound port and
  the server holds no key for it. A plain `ssh -R 1080` (reverse *dynamic* forward) would build an ACL-less
  SOCKS proxy into ssh, letting the server ask it to connect to `192.168.x.x`, the router, or home's own
  `127.0.0.1:22`. So the forward targets a **Dante daemon** instead
  (`ssh -R <bind>:1080:127.0.0.1:1080`) whose rules deny loopback + RFC1918 + link-local and allow only
  80/443 outbound. `check-tunnel.sh` asserts those denials actually bite, against hosts that really exist -
  a deny test against a non-existent host passes even with the rules removed.
- **Bind address is the docker bridge gateway (`172.17.0.1`), not `127.0.0.1`**, because FlareSolverr runs
  in a bridge-network container where `127.0.0.1` is the container itself. A loopback-bound tunnel is
  invisible to it, so every anidb/anime-planet/simkl request fails with a proxy connection error (Chrome does
  not fall back to direct, so it is loud rather than a silent ban). The trap is that the host-side checks all
  still pass, which is why `check-tunnel.sh` probes from inside the container too. Needs
  `GatewayPorts clientspecified` on this server's sshd, scoped to the tunnel account.

**Two gotchas worth remembering**, both covered in `scripts/tunnel/README.md`: the server's sshd is not on
port 22 (so `ssh -p` / `scp -P`), and **ufw silently drops container-to-host traffic** (container -> host port
traverses INPUT, which docker's rules do not bypass), needing
`ufw allow from 172.17.0.0/16 to 172.17.0.1 port 1080 proto tcp`. Its signature is check 4 reporting *timed
out* (dropped) rather than *refused* (nothing listening).

**Remaining:** run the full crawl, then re-run the merge-lock bootstrap per the ordering below. Read item 3
first - the first full crawl is far slower than it needs to be.
- **Re-run the merge-lock bootstrap after reactivating.** `scripts/bootstrap-merge-locks.py` rebuilds
  `merge.lock` (+ `checked-isolated-entries.txt`) from an upstream anime-offline-database release; canonical
  copies live in `review-data/`. The initial import (2026-07-14) used `--restrict-to-fork` so `merge.lock`
  only references providers the fork actually crawls, because `DeadEntriesValidationPostProcessor` aborts on
  merge.lock sources that have no DCS file (an uncrawled provider looks "dead" to it). This is why ~3,745 of
  the ~3,846 unreviewed cluster-1 entries are not new anime but fork halves of upstream pairs whose partner
  provider (anidb / anime-planet / simkl / anisearch) is not being crawled: `--restrict-to-fork` strips the
  uncrawled partner, the group collapses below two sources, and it is skipped. Reactivating those providers
  folds all of them back in at once. Do it in this order:

  1. Tunnel up, then remove the four hostnames from `deactivatedMetaDataProviders` in `config.toml`.
  2. Run a full crawl so anidb/anime-planet/simkl/anisearch land in the fork-db. This is the step that makes
     the difference - the bootstrap can only keep sources the fork-db already contains.
  3. Regenerate the bootstrap against that fresh fork-db, **keeping `--restrict-to-fork`**, and copy the new
     `merge.lock` (+ `checked-isolated-entries.txt`) into place.
  4. Reprocess. The reunited cross-provider groups lift "reviewed" from ~89% back toward upstream's ~98%.

  Keep `--restrict-to-fork` on the re-run - it is the robust choice, not `--restrict-to-fork`-off. Once a
  provider is crawled its URLs are in the fork-db, so the flag keeps them; it only ever drops providers that
  are still not being crawled, which is exactly what you want (dropping `--restrict-to-fork` would put those
  still-missing providers' URLs back into `merge.lock` and re-trigger the dead-entries crash). Dropping the
  flag is only safe once every provider is crawling. See the "Dead-entries validator" note in README's
  Possible improvements for the code change that would remove the `--restrict-to-fork` requirement entirely.

### The first crawl after reactivation is a big one

DCS state at reactivation (2026-07-17):

| Provider | DCS entries | First run does |
| --- | --- | --- |
| anisearch.com | 18,828 | re-downloads **all** of them: last fetched `2026-W28`, `_nextDownload` was `2026-W29`, which passed while it was off, so every entry is overdue |
| anidb.net | 0 | full initial crawl (~14.5k) |
| anime-planet.com | 0 | full initial crawl (~26.7k) |
| simkl.com | 0 | full initial crawl (~14.5k) |

Nothing is broken by the overdue anisearch schedule (`DownloadControlStateWeeksValidationPostProcessor`
skips deactivated providers, and 18.8k is the same volume a normal week already did). The real cost is the
three zero-history provider(s) crawling everything from scratch through a serialized FlareSolverr - see
item 3.

If the anisearch catch-up alone ever needs to be smaller, spread the schedule out beforehand with:

    scripts/reschedule-dcs.py --hostname anisearch.com --spread 1-6 --apply

That rewrites only `_nextDownload` (never `_lastDownloaded`) and turns the single catch-up run into
several smaller ones. The trade-off is that entries pushed further out keep serving their `2026-W28`
data until their week arrives, so only do it if the one-shot run is actually a problem.

Note the interaction with fail-fast: a multi-day run aborts entirely if any one provider fails. Downloads
are not lost (raw/conv files persist and `AlreadyDownloadedIdsFinder` skips them on a re-run), but a re-run
calls `DefaultDownloadControlStateUpdater.updateAll()` a second time in the same week, which is the trap
described under "Smaller follow-ups".

### Point the anisearch retry cases at the right failure once tunneled

`AnisearchCrawler`, `AnisearchLastPageDetector` and `AnisearchPaginationIdRangeSelector` each register
`ThrowableRetryCase(executeBefore = restart) { it is ConnectException }` (plus `UnknownHostException` /
`NoRouteToHostException`), where `restart` restarts the network interface. That diagnosis assumes the error
means "our datacenter IP/interface is the problem". Once anisearch is routed through the tunnel a
`ConnectException` much more likely means **the tunnel died**, and restarting the interface cannot fix that -
it drops the SSH connection home established and forces autossh to reconnect, delaying recovery rather than
helping.

Not harmful (the run fails fast and autossh does come back), but the retry is aimed at the wrong failure.
Consider skipping the interface restart for tunneled provider(s) and surfacing a tunnel-specific error
instead, so the log says "tunnel is down" rather than silently cycling the network. `TunnelConfig.isTunneled`
already provides the predicate.

## 3. Reuse a FlareSolverr session instead of re-solving the challenge every request

**DONE (2026-07-17). Measured ~180h -> ~40h for the first full crawl.**

`FlaresolverrHttpClient` now creates one session per metadata provider on first use (`sessions.create`),
passes `"session": "<id>"` on every request, and destroys them all at the end of the run via
`destroyFlaresolverrSessions()` in `App.kt` - called before `stopFlaresolverr`, since destroying a session is
itself a request to the container, and because `startFlaresolverr` deliberately reuses a running container so
nothing else would ever reclaim them.

Details worth keeping in mind:
- **The `proxy` moved to `sessions.create`.** A session keeps the proxy it was created with, so a request
  carrying only `session` still exits through the tunnel - verified by asking `api.ipify.org` through a
  session and getting the residential IP back, not the datacenter one. This was the risk: had the session
  ignored the proxy, every request would have silently used the banned IP.
- **One session per provider**, because a session is a browser and its Cloudflare clearance cookie is
  per-domain. Created under a mutex, since several crawlers hit the same provider concurrently (e.g. the
  anidb crawler and its highest-id detector) and would otherwise each create one and leak all but the last.
- **A lost session is retried once** with a fresh one. That is a transient fault, not a provider blocking
  us, so it must not abort a multi-day crawl - unlike a genuine block, which still fails fast.
- **`sessions` is a companion-object map** (same rationale as `flaresolverrSemaphore`: one provider, one
  session, however many crawler instances). The test suite runs concurrently, so tests touching it are
  serialized with `@ResourceLock` - without that they clear each other's sessions.
- A crashed run leaks its sessions; they die with the container whenever it is next restarted.

Original analysis, kept because it explains the numbers:

`FlaresolverrHttpClient` used to send every request as a bare `request.get` / `request.post`. FlareSolverr
answers each one in a fresh browser context, so anidb's Cloudflare challenge was solved **from scratch on
every single entry**. Measured through the tunnel on 2026-07-17:

| | 1st request | 2nd | 3rd |
| --- | --- | --- | --- |
| no session (current behaviour) | 18.8s | 18.5s | 18.5s |
| `sessions.create` + `"session": id` | 18.5s | **3.3s** | **2.0s** |

The challenge is solved **once per session**, not per request. Everything after the first call reuses the
browser and its clearance cookies.

Why it dominates the schedule: FlareSolverr is serialized globally
(`FlaresolverrConfig.maxConcurrency` defaults to `1`, and `flaresolverrSemaphore` is in the companion object,
so it is shared by every instance). anidb, anime-planet and simkl therefore queue through one browser rather
than running in parallel, and wall-clock is the **sum** of their FlareSolverr time, not the max:

| Provider | entries | per request | contribution |
| --- | --- | --- | --- |
| anidb.net | 14,517 | 18.7s | 75h |
| anime-planet.com | 26,674 | 9.2s | 68h |
| simkl.com | 14,494 | 9.1s | 37h |
| | | | **~180h total** |

(anisearch is a direct scrape, genuinely parallel, ~29h - it is not the long pole despite having the most
overdue entries.) At ~2-3s per request the same work is roughly 40h. Raising `maxConcurrency` is *not* the
alternative: one container has one browser and returns HTTP 500 under concurrent load, which is exactly why
the semaphore exists.

**Verified against the live container and tunnel (2026-07-17)**, driving the real `FlaresolverrHttpClient`
rather than curl:

    anidb/1535 -> http=200 bytes=147874 in 19295ms   <- creates the session, solves the challenge
    anidb/23   -> http=200 bytes=654781 in  3132ms   <- reused
    anidb/17   -> http=200 bytes=400158 in  1940ms   <- reused
    anidb/30   -> http=200 bytes=678090 in  1213ms   <- reused
    anidb/44   -> http=200 bytes=246448 in   858ms   <- reused
    simkl      -> http=200 bytes=671510 in 10830ms   <- its own session, its own one-off challenge
    sessions.list afterwards -> 0                    <- destroyFlaresolverrSessions() reclaimed them

Only the first call per provider pays the challenge, and it keeps getting faster as the browser warms. A
warm request averages **~1.8s**, which puts the first full crawl at roughly:

| Provider | entries | warm | browser time |
| --- | --- | --- | --- |
| anidb.net | 14,517 | ~1.8s | 7.3h |
| anime-planet.com | 26,674 | ~1.8s | 13.3h |
| simkl.com | 14,494 | ~1.8s | 7.2h |
| | | | **~28h queued** |

anisearch is a direct scrape running in parallel at ~29h, so it is the long pole again and the whole run is
back to roughly a day - versus ~180h before this change.

**Re-challenges are FlareSolverr's own business**, not something this code handles: it inspects every request
for a challenge and solves it in place, session or not (`anidb/1535` above went through a session and still
reported "Challenge solved!"). If clearance lapses mid-crawl, one entry costs ~19s and the rest stay fast.
What the retry here covers is the different case of the session itself being *gone*.

## 4. simkl: the pagination POST is blocked by Cloudflare even from a residential IP

**Blocks simkl entirely. Found 2026-07-17 during the first crawl after the tunnel went up.** simkl is the
only provider still producing no files; run it with `--skip simkl` (or put `simkl.com` back into
`deactivatedMetaDataProviders`) until this is fixed, otherwise it eventually exhausts its retries and
fail-fast aborts the whole run.

`SimklPaginationIdRangeSelector` fetches pages with a **POST** to `https://simkl.com/ajax/full/anime.php`.
FlareSolverr cannot get through it:

    Error: Error solving the challenge. Cloudflare has blocked this request.
    Probably your IP is banned for this site, check in your web browser.

**It is not the IP, and it is not the session change.** Both were checked rather than assumed:

| Test | Result |
| --- | --- |
| simkl GET (`/anime/46128`) via tunnel | `200`, 669 KB - fine |
| simkl POST (`/ajax/full/anime.php`) via tunnel **with** session | Cloudflare blocked |
| simkl POST via tunnel **without** session (the pre-session code path) | Cloudflare blocked - identical |
| `request.post` to httpbin via a session, checking `origin` | `142.188.238.43` - the POST **does** exit through the tunnel |

So POSTs are correctly proxied, GETs to simkl work, and only this AJAX POST is refused. FlareSolverr's
"Probably your IP is banned" is just its generic wording for a block page and is misleading here.

This was invisible until now: simkl has **0 DCS entries**, having never once been crawled successfully, so
nothing ever exercised this path. The tunnel fixed simkl's IP ban and uncovered a second, unrelated problem
behind it.

**Where to start.** FlareSolverr's `request.post` submits a form in the browser, and a Cloudflare challenge
mid-submit cannot be re-driven the way a GET redirect can - so a challenged POST is close to unsolvable by
design. Options, roughly in order of promise:
- find a GET-based pagination route (`/anime/all/...` renders server-side and GETs already return 200);
- warm the session with a GET of the referring page first, so the POST carries clearance cookies plus a
  plausible `Referer`/`Origin`, and see whether the challenge stops appearing at all;
- failing both, drive simkl's pagination outside FlareSolverr - a direct `tunnelAwareHttpClient` POST through
  the tunnel, which is unchallenged only if simkl does not gate that endpoint on JS.

## 5. Make the suite fail when a provider's live HTML changes, not just when a fixture does

**The gap that let two crawl-stopping bugs reach production on 2026-07-17.** Every provider's tests run on
every build - they are *not* gated on `deactivatedMetaDataProviders` (`TestAppConfig` throws
`shouldNotBeInvoked()` if anything reads it). They were green the whole time anime-planet was broken, because
they parse `page-31.html`: a snapshot captured at some point in the past. The test proves "the parser handles
this saved HTML", never "the parser handles what the provider serves today". The fixture is frozen; the
website is not.

What that cost:
- **anime-planet** silently switched its listing from a card grid (`li[data-type=anime]`) to a table
  (`td[class=tableTitle]`). Tests green, live crawl dead on page 1. Fixed by accepting both layouts, but only
  after it crashed a run.
- **simkl** (item 4) fails on a POST that no test exercises at all.

Both hid behind the same thing: those provider(s) had **0 DCS entries**, so nothing had ever compared the
parsers against reality. Note the fixture added for the table layout is itself a fresh snapshot - it starts
going stale today, exactly like the last one did.

**Proposal: a live contract check per provider**, deliberately outside the normal suite.

For each provider, fetch the very page the crawler fetches and assert its selectors still match something -
not a full parse, just "does `//td[@class='tableTitle']/a/@href` still find entries?". That single assertion
would have caught anime-planet on the day it changed. Cover each entry point that has a selector or an
endpoint of its own:
- pagination / id-range selectors (this is where anime-planet broke),
- highest-id / last-page detectors,
- and simkl's AJAX POST, which is not a selector problem but the same class of "only the live site knows".

Wiring, so it is first-class but never runs in CI:
- tag them `@Tag("live")` and add `useJUnitPlatform { excludeTags("live") }` to the normal `test` task;
- add a `liveCheck` gradle task which runs *only* that tag;
- run it on a schedule, and before kicking off a long crawl - the cost of finding this at minute 6 of a 30h
  run versus minute 6 of a 30s check is the entire point.

`LiveSessionSmokeTest` (currently `@Disabled`, needs the tunnel + FlareSolverr) is the seed of this: same
shape, same infrastructure requirements. Converting it to `@Tag("live")` and adding one such test per
provider is the concrete first step.

**Do not "fix" this by refreshing the fixtures, and do not replace the fixture tests with live ones.** The
two answer different questions and both are needed:

| | answers | when it runs |
| --- | --- | --- |
| frozen fixture (existing tests) | did *our code change* break the parser? | every build, fast, deterministic |
| live contract check (this item) | did the *provider* change under us? | scheduled / before a crawl |

A fixture is only a regression test *because* it is frozen. Auto-refreshing one would defeat it: a selector
this fork breaks would keep passing against freshly fetched HTML that happens to match whatever it now looks
for. And fetching live in the normal suite would make it slow, networked and flaky, while losing the ability
to tell "we broke it" from "they changed it". Keep `page-31.html` and `page-table-layout.html` exactly as
they are; add the live check alongside.

A stronger variant, if it proves worthwhile: have the live check **re-capture** a fixture to a scratch path
and diff it against the committed one, reporting drift as a reviewable change rather than a bare pass/fail -
it would tell you *what* changed, not just that something did. It must never overwrite the committed fixture
in place, for the reason above.

Note this does not replace `check-all-providers.sh`, which only probes reachability. Reachability was never
the problem here: anime-planet returned a perfectly healthy `200`.

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
