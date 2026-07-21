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

**DONE (2026-07-17).** `modb.app.tunnel.enabled = true`, `danted` + `autossh` run on the home machine, and
`scripts/tunnel/check-tunnel.sh` passes all four checks. All four IP-banned provider(s) became reachable from
the residential IP - verified against the live services rather than assumed:

| Provider | direct (datacenter) | via tunnel (residential) |
| --- | --- | --- |
| anidb.net | FlareSolverr: *"Cloudflare has blocked this request. Probably your IP is banned for this site"* | `200`, "Challenge solved!" |
| anime-planet.com | 403 | `200`, "Challenge not detected!" |
| simkl.com | 403 | `200`, "Challenge not detected!" |
| anisearch.com | TCP refused (curl exit 7) | `301` |

Cloudflare naming the IP ban outright, and anime-planet/simkl not even being challenged from a residential
IP, confirms the block was purely IP-based exactly as diagnosed.

**But the ban was hiding a separate blocker behind each provider**, none of them knowable until it lifted, and
a residential exit turned out not to be permanent either, so only one of the four still crawls today:

| Provider | Now | Why |
| --- | --- | --- |
| anime-planet.com | **crawling** | its HTML had drifted to a table layout while the parser expected cards; fixed by accepting both |
| anisearch.com | **blocked** | crawled for a day, then anisearch banned the residential IP too - item 6 |
| simkl.com | **deactivated** | pagination POST refused even from residential - item 4 |
| anidb.net | **deactivated** | ~100-200 requests per IP per *day*; needs rotating residential proxies - item 5 |

So `deactivatedMetaDataProviders` holds `anidb.net`, `anisearch.com`, and `simkl.com` (anisearch added
2026-07-17, see item 6), and the merge-lock re-run below will still leave their cross-provider splits split.

**The residential IP is a consumable, not a fix.** Two of the four have now burned it (anidb within ~50-80
requests, anisearch within a day), which the table above records provider by provider. The tunnel buys an
exit that is not pre-banned; it does not buy permission to crawl at datacenter speed from it.

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

## 4. simkl: enumeration is dead, but a keyless MAL-driven redirect scrape is viable

**UPDATED 2026-07-20: enumeration is still dead, but it turns out enumeration is NOT REQUIRED.** simkl has no
reachable way to enumerate its anime IDs by any path (scrape or API - the three findings below each close a
different avenue, verified not assumed). BUT modb is a cross-provider merger that already holds ~30.6k MAL ids,
so it can invert the problem and resolve each known anime into its simkl entry via simkl's redirect service -
and that redirect works **keyless** (no `client_id`, verified below), which removes the "killable key" objection
that was the entire reason this route was previously rejected. So the technical "not achievable" is no longer
true. What remains is a ToS-spirit judgement call (see the redirect note) plus the engineering, weighed
against the fact that simkl has had **0 DCS entries** and is a marginal 9th source. Implementation sketch is at
the end of this item.

**(Earlier verdict, now superseded, kept for context: "RESOLVED as NOT ACHIEVABLE 2026-07-19 - no reachable
enumeration by any path, keep permanently `--skip simkl`." That was correct about enumeration but wrong to
conclude simkl is unreachable, because it did not account for driving off modb's own MAL ids with a keyless
redirect.)**

**Why every path is closed (verified 2026-07-19 from a real residential browser + the API spec):**

1. **The pagination POST is dead even for a real human.** A hand-replayed POST to `/ajax/full/anime.php`
   from real Chrome, carrying a **valid `cf_clearance` cookie**, correct `Origin`/`Referer`/`x-requested-with`
   /`sec-fetch-*` and a real UA, still returns **403** with `server-timing: cfEdge;dur=2,cfOrigin;dur=0` - i.e.
   Cloudflare's **edge WAF rejects it before it ever reaches simkl's origin**. This is a firewall/bot-management
   rule ("Sorry, you have been blocked... the action you just performed triggered the security solution"), NOT a
   solvable JS/cookie challenge. => `cf_clearance` warming, FlareSolverr, and header/UA spoofing are ALL dead:
   if genuine Chrome with valid clearance gets 403, nothing modb can send will do better.
2. **The GET year-page can't be paginated either.** `/anime/all/{year}/a-z/` GETs return 200 and server-render
   only the first page (~20 entries); **scrolling the live page in a real browser loads NO further entries** -
   the infinite-scroll fires that same dead POST. So GET-only enumeration caps at ~first-page-per-year and the
   site itself offers a human no way past it. Insufficient and unfixable.
3. **The public API has no enumeration endpoint.** Verified against the OpenAPI rewrite at `api.simkl.org`
   (cross-checked vs the raw Apiary spec and `api.simkl.org/llms.txt`). The only anime GETs are `/anime/{id}`,
   `/anime/episodes/{id}`, `/anime/airing`, `/anime/genres`, `/anime/best`, `/anime/premieres`. There is **no
   `/anime/all` / bulk dump / full-ID-list endpoint**; the discovery routes are filtered/ranked slices (max
   50/page) that can't be walked to reconstruct the full ~14.5k ID set. The one thing modb needs - complete
   enumeration - the API structurally cannot provide.

**The invert-the-problem route works, and it is keyless (verified 2026-07-20).** modb already holds ~30.6k MAL
ids, so for each anime it knows it resolves the simkl entry via simkl's redirect
(`GET https://api.simkl.com/redirect?to=simkl&mal=<id>`), sidestepping both the dead POST and the missing
enumeration endpoint. The docs claim `client_id` is required, but the endpoint does NOT enforce it - tested with
no key at all:

| MAL id | result (no client_id) |
| --- | --- |
| 1 (Cowboy Bebop) | 301 -> `https://simkl.com/anime/37089/cowboy-bebop` |
| 20 (Naruto) | 301 -> `https://simkl.com/anime/39508/naruto` |
| 5114 (FMA: Brotherhood) | 301 -> `https://simkl.com/anime/41586/hagane-no-renkinjutsushi` |
| 2 (does not exist on MAL) | 301 -> bare `https://simkl.com/` (clean miss signal) |

So there is NO key to revoke and NO API terms to sign - the earlier "knowingly violating signed terms from a
killable key" objection is void. `robots.txt` also allows it: `/anime/` and `/redirect` are not disallowed for
`*` (only `/search/`, which is unused). What is NOT void is the ToS SPIRIT: Rule 2 (no catalog use by a
competing tracker without Simkl login+sync) and Rule 3 ("Simkl is built for tracking and discovery, not as a CDN
for the world's metadata") are general positions that arguably still cover an unauthenticated scrape, and simkl
could respond by IP-blocking the tunnel. That is a materially reduced risk (no account or key at stake, only the
tunnel IP that is already dedicated to banned providers) but still a deliberate policy decision, not the
crawler's own judgement. Same class of collision as anisearch's Custom Interface clause (item 6).

**Historical context (kept for reference).** The block was found 2026-07-17 during the first crawl after the
tunnel went up - simkl was the only provider producing no files. The earlier read (below) that it was merely
"a challenged POST, close to unsolvable" underestimated it: it is a hard edge WAF block, not a challenge.

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
| `request.post` to httpbin via a session, checking `origin` | the home IP, not the datacenter one - the POST **does** exit through the tunnel |

So POSTs are correctly proxied, GETs to simkl work, and only this AJAX POST is refused. FlareSolverr's
"Probably your IP is banned" is just its generic wording for a block page and is misleading here.

This was invisible until now: simkl has **0 DCS entries**, having never once been crawled successfully, so
nothing ever exercised this path. The tunnel fixed simkl's IP ban and uncovered a second, unrelated problem
behind it.

**Superseded options (why the once-promising routes were abandoned).** The earlier plan was to (a) find a
GET-based pagination route, (b) warm the session with a GET so the POST carried clearance cookies, or (c) drive
the POST outside FlareSolverr through the tunnel. Finding 1 above kills (b) and (c) - a real browser with valid
`cf_clearance` already gets an edge 403, so no client-side variant helps. Finding 2 kills (a) - the live page
won't paginate past the first page even for a human. The API was floated as a ban-free path but finding 3 shows
it cannot enumerate. The route that DOES work is not pagination at all but the keyless MAL-driven redirect
resolution above - it never asks simkl to list itself.

**Implementation sketch (if the ToS risk is judged acceptable).**
- Replace `SimklPaginationIdRangeSelector` (the dead `/ajax/full/anime.php` POST) with a MAL-id-driven resolver.
  Everything downstream - download, convert, DCS - is unchanged; only ID acquisition changes.
- Driver ids: read modb's existing myanimelist DCS entries via `DownloadControlStateAccessor` and pull the MAL
  id from each source URL (~30.6k). simkl is a SEED here - it has 0 DCS entries, so the first run resolves the
  whole set; later runs only refresh due entries like any other provider.
- Resolve: `GET https://api.simkl.com/redirect?to=simkl&mal=<id>` with redirect-following DISABLED, read the
  `Location` header. If it matches `^https://simkl.com/anime/(\d+)/` capture the simkl id (hit); if it is bare
  `https://simkl.com/` there is no mapping (skip). If the shared http client cannot be told not to follow 301s,
  add a no-follow variant or inspect the FINAL url after following (a followed miss just loads the homepage once).
- Fetch + parse: `GET https://simkl.com/anime/<simklId>` (plain HTML, returns 200 via tunnel) into the existing
  `SimklAnimeConverter`. No FlareSolverr needed - neither the 301 nor this GET hits a Cloudflare challenge; only
  the abandoned `/ajax` POST did.
- Routing: simkl is already in `[modb.app.tunnel] providers`, so both calls exit via the residential IP (the
  datacenter IP is simkl-banned). Two requests per anime (redirect + page).
- Rate: UNTESTED at volume. No safe rate is known for ~30k+ unauthenticated redirects. Start conservative and
  DOCUMENT the intended rate next to the delay - same lesson as items 5/6, where a silent speedup deleted an
  accidental rate limiter and banned the IP. Do not hammer to find the threshold.
- Test vehicle: `--only simkl` re-enables the config-deactivated simkl and runs ONLY it through the tunnel, so it
  never touches the other 7 providers' DCS and fail-fast only aborts the simkl run. This is the targeted-run
  payoff of item 1, and it is cheap this week because last week's full crawl already populated the others.
- Coverage: only anime with a MAL id that simkl maps get resolved; simkl-only titles are missed. Acceptable for
  a MAL-centric merger, and better than today's 0 entries.

## 5. anidb: AntiLeech triggered - the session speedup removed the accidental rate limiter

**anidb flagged the residential IP on 2026-07-17 and the run aborted. Keep `--skip anidb` until this is
resolved**, and do not retry against a flagged IP - that risks extending the ban.

    io.github.manamiproject.modb.anidb.CrawlerDetectedException: Crawler has been detected

`AnidbResponseChecker` throws this when the response title is `AniDB AntiLeech - AniDB` or `403 Forbidden`.
Confirmed still being served to the home IP after the crash, so the ban is on the **residential** IP, not the
datacenter one. AntiLeech bans are normally temporary.

**Cause: item 3 made anidb roughly 5x faster.**

| | per entry | requests/hour |
| --- | --- | --- |
| before sessions | ~19s solve + ~3s delay | ~165 |
| after sessions | ~1.5s + ~3s delay | **~800** |

The Cloudflare challenge had been an *accidental rate limiter*: every request cost ~19s whether we liked it
or not, and `random(2500, 3500)` was never doing the throttling. Sessions removed that ballast and the real
delay turned out to be far too small. Nothing in the codebase expressed the intended request rate, so nothing
noticed it change.

**The real constraint is a DAILY QUOTA, not a rate - so no delay fixes this.** anidb is reported to allow
only **~100-200 requests per IP per day** (the figure comes from FileBot's guidance around anidb's API; the
website's AntiLeech threshold is undocumented but empirically just as low - this run was flagged after
roughly 50-80 requests across two short runs plus some manual probing).

Separately there is a **flood limit of 2 requests per 5 seconds**, i.e. ~2.5s apart. The existing
`random(2500, 3500)` already satisfies *that*. The flood limit was never the problem.

What the quota means for a full seed of 14,517 entries:

| requests/day | time for ONE full seed |
| --- | --- |
| 100 | ~145 days (4.8 months) |
| 150 | ~97 days (3.2 months) |
| 200 | ~73 days (2.4 months) |

Staying under 150/day needs ~576s (~10 min) *between requests*. For scale, an earlier draft of this item
suggested "10s+ per entry, start conservative" - that is **8,640 requests/day, ~58x over the quota**. Any
delay worth running is orders of magnitude too fast. This is not a tuning problem.

**Steady state may not fit either.** DCS backoff caps at 12 weeks, so a seeded anidb still needs
14,517 / 84 days = **~173 requests/day** just to keep every entry inside its refresh window - already at or
above the quota, before any new entries. Worth measuring before investing in a seed that cannot then be
maintained.

**The official API is not the way out, and this is settled - do not re-investigate it.** Upstream already
went down this road: they built a workaround for the problem below ("Workaround in place which reduced the
number [of] calls") and still abandoned it, because "the integration test showed that this public API
endpoint is extremely sensitive. Too sensitive for a productive usage. Therefore I see no use for this." So
the API is a tried-and-rejected option, not an untried one.

The underlying reason it needs a workaround at all - and *why* the app scrapes HTML in the first place - is
that the API cannot distinguish a **deleted** entry from one **pending addition**; both come back as "not
found":

| case | UI | API |
| --- | --- | --- |
| deleted (e.g. 14248) | distinguishable | "not found" |
| pending addition (e.g. 19982) | distinguishable | "not found" |

That distinction is load-bearing: deleted entries are recorded in the dead-entries files and never requested
again, while pending ones must be retried because they may appear later. Losing it means either permanently
dropping entries that were only pending, or re-requesting dead ones forever - which *increases* the number of
calls, against the very quota that is the constraint. So the API trades the one thing we need for nothing we
lack.

**Decision (2026-07-17): anidb is effectively dropped, not just paused. It stays out of `config.toml` until it
has auto-rotating residential proxies - which is not on any near-term plan - so treat the dataset as
permanently carrying no anidb sources unless that changes.** With 0 DCS entries there is nothing already
captured to fall back on either (contrast anisearch, item 6, which keeps 18,828 frozen entries when off). This
is not a "revisit soon" item; it is parked pending an infrastructure change that has to come first.

**Slowing down cannot rescue this one** - the contrast with anisearch (item 6) is the whole point.
anisearch's ban looks volume-triggered, so a long enough delay may genuinely fit under it; anidb's limit is a
*daily quota per IP*, and a delay cannot buy throughput that the quota does not contain. Do not spend time
tuning a delay here.

That conclusion follows directly from the constraint. The quota is **per IP**, so the only thing that raises
throughput is *more IPs* - not a longer delay, not the API, not a smarter crawler. One residential IP buys
~100-200 requests/day against a 14,517-entry seed that then needs ~173/day forever just to stay refreshed.
A single home connection cannot do this, and pointing the whole seed at it means months of daily AntiLeech
risk on the home line.

Consequences to accept meanwhile: the dataset carries no anidb sources. The merge-lock bootstrap does hold
anidb URLs from upstream, but `--restrict-to-fork` drops them precisely because there is no anidb DCS - so
those cross-provider splits stay split and "reviewed" lands short of upstream's ~98%.

**Rotating this host's IPv6 instead is not a shortcut - measured 2026-07-17, do not retry.** The idea is
reasonable (the /64 holds effectively unlimited addresses, and it had genuinely never been tested: docker's
default bridge has `EnableIPv6=false`, so FlareSolverr had only ever reached anidb over IPv4). Driving a
FlareSolverr container on host networking, confirmed egressing from this host's own IPv6:

| path | result |
| --- | --- |
| datacenter IPv6 | `error` - "Cloudflare has blocked this request. Probably your IP is banned for this site" |
| datacenter IPv4 | identical |

Note plain `curl` cannot tell you this: both families return a 403 *challenge* page, and the block only
appears once the challenge is actually solved. The browser is required to see it.

Two reasons it could not have worked anyway: Cloudflare treats a `/64` as one customer, so rotating inside
the host's routed /64 looks like a single host; and the block is on Hetzner's address space rather than on any individual address
that went hot.

If it is ever revisited, a rotating-residential-proxy pool is the entry ticket, and the open questions are
then: a per-provider "max entries per run" cap (does not exist today), and whether the *website's* AntiLeech
threshold actually matches the quoted API figure - measuring that itself costs quota and risks an IP.

Worth being clear-eyed about what that pool would be doing: anidb's limit is deliberate, and AntiLeech exists
to stop exactly this. The tunnel routes *around a ban on datacenter ranges* to visit the site as an ordinary
residential client, still inside their per-IP budget. A pool sized to pull 14,517 pages is instead defeating
the budget itself, and the realistic response from a provider that already ships AntiLeech is to block harder.

**Also fix regardless: stop rotating the datacenter IP for tunneled provider(s)** - see the note below. It
cannot help, and it stalls every other provider while it happens.

**Note for whoever picks this up:** debugging anidb burns the same quota, from the same home IP. Budget it.

### Rotating the datacenter IP cannot help a tunneled provider, and it hurts

Generalises the anisearch note that used to live here; the anidb crash proved it.

`AnidbCrawler` reacts to `CrawlerDetectedException` by calling `networkController.restartAsync().await()` and
retrying once. That logic predates the tunnel and assumes our exit IP is this host's. It no longer is: anidb,
anime-planet and simkl exit through the tunnel from the *residential* IP, so rotating the server's IPv6
changes nothing about what the provider sees. Observed in the crash log:

    02:18:32 IPv6 address rotation has been triggered.
    02:18:33 Waiting for network to be active again.   <- every other provider stalls
    02:18:35 Waiting for network to be active again.
    02:18:37 CrawlerDetectedException: Crawler has been detected   <- retry hit the same flagged IP

So the recovery is useless (same exit IP), harmful (`SuspendableHttpClient` blocks all providers while the
interface bounces), and guaranteed to fail (it retries into the very ban it is reacting to). The same applies
to anisearch's `ConnectException`/`UnknownHostException`/`NoRouteToHostException` retry cases, where a
connection failure now means either the tunnel died or anisearch refused the exit IP (item 6, and as of
2026-07-17 it is the second one). A network restart fixes neither, and actively delays recovery, since it
drops the SSH connection and forces autossh to reconnect.

Note the ban does not even surface as those exceptions while tunneled: a refused connection arrives as
`SocketException: SOCKS: Connection refused` (dante relaying SOCKS reply 5), so the retry cases above do not
match it and the network restart never fires for anisearch today. That is the right outcome by accident, not
by design - the same fix applies.

`TunnelConfig.isTunneled(hostname)` already provides the predicate. For a tunneled provider the honest
responses are to back off, or to fail fast with a message naming the real cause, rather than to cycle an
interface that is not in the path.

## 6. anisearch: the residential IP is banned too - the one ban that may really be a delay problem

**Decision (2026-07-17): deactivated and FROZEN, kept as a revisit - not dropped.** anisearch is now in
`deactivatedMetaDataProviders`, which unblocks the crawl and keeps its 18,828 already-downloaded entries merged
into the dataset (they stop refreshing, nothing more). Because the ban looks volume-triggered rather than a hard
quota, the slow-crawl experiment below is still worth trying on a fresh IP someday; until someone does, the
frozen data stands and this costs nothing. Contrast anidb (item 5), which is parked pending proxies with no
saved data to fall back on.

**anisearch TCP-refused the home IP as of 2026-07-17, and was the fail-fast blocker until deactivated:** every
request burned its five retries and then aborted the whole run, so no other provider finished until anisearch
was added to `deactivatedMetaDataProviders`. Confirmed from the home connection itself - the site serves a
browser there normally, so this is a ban on the exit the tunnel uses, not an outage.

**How it presents.** There is no challenge and no 403, just a SOCKS error, which is why it does not look like a
ban at first:

    WARN DefaultHttpClient - [GET https://anisearch.com/anime/1176] failed with
    [SocketException: SOCKS: Connection refused] - retrying (attempt 2 of 5).

That is dante relaying SOCKS reply 5: its own `connect()` on the home machine got ECONNREFUSED. The tunnel is
healthy and the refusal is anisearch's alone:

| through the tunnel, to | result |
| --- | --- |
| `1.1.1.1:443` | SOCKS5 request granted |
| `168.119.4.235:443` (anisearch) | reply 5, connection refused |

TCP-refused is anisearch's ban signature - the same one item 2's table records for the datacenter IP ("TCP
refused (curl exit 7)"). The tunnel did not stop working; the ban followed it to the home IP.

**Why this one may be salvageable, unlike anidb.** anidb enforces a documented ~100-200 requests per IP per
*day* that no delay can fit (item 5). anisearch publishes no such quota, its ban looks volume- or
rate-triggered, and TCP rejects of this kind are usually temporary. So here a delay that is actually slow
enough may be a real fix rather than a rounding error.

**The rate that burned it.** `AnisearchCrawler` waits `random(4000, 7000)` ms (~5.5s average) and each entry
costs at least two requests, `/anime/<id>` and `/anime/<id>/relations` - order of ~1,300 requests/hour. That was
pointed at the 18,828-entry catch-up described under item 2, where every entry came due in one burst, and the
IP was refused well inside the first day. **The actual threshold is not measured** - that is the open question,
and it is the number the delay should be derived from.

**Next steps, cheapest first.**
1. ~~Add `anisearch.com` to `deactivatedMetaDataProviders` so the rest of the crawl can finish.~~ DONE
   2026-07-17. No rebuild needed.
2. Wait the ban out, re-probing with a single `curl -sv https://anisearch.com/` from the server. If it lapses on
   its own it is a temporary rate ban and the rest of this is worth doing; if it never lapses, treat anisearch
   like anidb and leave it off.
3. Shrink the catch-up *before* re-enabling, so the first run back is not 18.8k entries at once:

       scripts/reschedule-dcs.py --hostname anisearch.com --spread 1-6 --apply

4. Raise the delay, then re-enable. Nothing in the codebase expresses an intended request rate for anisearch -
   the same gap item 5 diagnosed for anidb, where a speedup silently deleted an accidental rate limiter that no
   test or comment had ever named. Whatever number is chosen, write the reasoning down next to it.

**Do not measure the threshold by hammering the flagged IP.** It is the home connection, and probing a live ban
risks extending it. Same warning as item 5.

**Possible ban-free path: anisearch has an OAuth API (`api.anisearch.com`) - but read the redistribution
clause before investing.** Two things learned so far:

- **The public API does not expose catalog metadata today.** It is "in development" and currently ships only
  three modules: OAuth 2.0, a User API, and the Ratings API (`GET/PUT/DELETE /v1/my/{anime|manga}/{id}/ratings`,
  Bearer token, scopes `ratings.anime`/`ratings.manga`, 10 req/min). Ratings is the authenticated user's own
  watchlist, not the anime metadata the crawl needs. So there is a sanctioned API surface and it is growing, but
  the endpoint that would feed the crawl is not among the live modules. Worth re-checking as it expands, and
  worth reading the User API module to see exactly what it returns.
- **Bulk metadata would come via a "Custom Interface", and its terms conflict with what modb is.** anisearch
  offers tailored access on request (`api@anisearch.com`), and says it is often free when a project provides
  value to them (visibility, collaboration, technical synergy) - an open aggregator that links back to anisearch
  plausibly qualifies. **But: "Redistributing any data obtained via the API to third parties is strictly
  prohibited."** modb-app publishes a merged dataset (it is a fork of the public anime-offline-database), which
  IS redistribution to third parties. So API-sourced anisearch data could not go into the published output
  without an explicit carve-out - exactly what the `api@anisearch.com` conversation would have to secure first.
  Note the scraped-HTML path carries no such explicit API-terms clause; switching to the API trades a technical
  ban for a redistribution prohibition. Resolve that clause before building anything against it.

If a metadata endpoint does become available and the redistribution question is settled, the API still beats
the scraper on mechanics: an authenticated endpoint with published limits has no reason to TCP-refuse us, and
we would size the delay to its stated limit from the start (10/min there is ~600/hour, ~31h for 18.8k entries)
rather than rediscovering the limit by getting banned. Two constraints to carry in regardless: that low
published rate, and a **mandatory meaningful User-Agent** - a generic UA is 403'd and can get the IP denied, so
even an unauthenticated probe must send `App/1.0 (contact)` or it risks burning the fresh IP. Cheapest first
test once on a new IP: `curl` with a proper UA against the API host to see what is reachable unauthenticated,
before touching OAuth.

## 7. Make the suite fail when a provider's live HTML changes, not just when a fixture does

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
