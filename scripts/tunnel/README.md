# Reverse SSH tunnel (residential exit IP)

`anime-planet.com`, `simkl.com` and `anisearch.com` ban this host's datacenter IP range outright.
FlareSolverr solves their Cloudflare challenge but cannot change the exit IP, and IPv6 rotation only shuffles
addresses within the same datacenter /64. They need traffic to leave via a **residential** connection - this
tunnel, one home IP.

`anidb.net` is banned too, but it enforces a per-IP daily quota that one home IP cannot beat, so it uses a
rotating commercial proxy on a separate FlareSolverr container instead. See
[Commercial rotating proxy (anidb)](#commercial-rotating-proxy-anidb) below. The two paths are mutually
exclusive: a provider uses the tunnel or the commercial proxy, never both.

## How it works

```
  HOME (residential)                                CRAWL SERVER (datacenter)
  ------------------                                -------------------------
  danted on 127.0.0.1:1080  <---- ssh -R ------  listens on 172.17.0.1:1080
        |                     (home dials out)          ^              ^
        v                                               |              |
   the internet                                  modb-app        FlareSolverr
   (residential IP)                              (anisearch)     (anime-planet, simkl)
```

**Home dials out to the server.** That single fact is what makes this safe:

* Home is the SSH *client*. It opens **no inbound port**, so it works behind NAT/CGNAT with no port
  forwarding.
* Home holds a key **for the server**. The server holds **no key for home** and cannot open a connection
  to it. There is no shell access in this direction, only the forwarded port.
* The forwarded port lands on a SOCKS daemon whose ACL **refuses to connect to anything on the home
  network** (see `sockd.conf`). So the only thing the server can do through the tunnel is reach the public
  internet on ports 80/443, from a residential IP.

This is why we do **not** use `ssh -R 1080` (reverse *dynamic* forwarding). That builds the SOCKS proxy
into ssh itself, with no ACL, letting whoever is on the server ask it to connect to `192.168.1.1`, a NAS,
or home's own `127.0.0.1:22`. Forwarding to a Dante daemon instead is what makes the restriction possible.

### Why the bind address is `172.17.0.1` and not `127.0.0.1`

FlareSolverr runs in a bridge-network container, where `127.0.0.1` is *the container itself*. A tunnel bound
to the server's loopback is invisible to it: FlareSolverr would be handed a proxy it cannot reach, and every
anime-planet/simkl request would fail with a proxy connection error (it does not fall back to a direct
request, so this is a loud failure, not a silent ban). `172.17.0.1` is the docker bridge gateway: reachable
from both the containers and the host, and **not routable from the internet**.

The trap this creates is a *misleading green light*: `checkTunnel` and checks 1-3 of `check-tunnel.sh` run on
the host, which can reach `127.0.0.1` fine. They would all pass while FlareSolverr is still broken. That is
exactly why `check-tunnel.sh` has check 4, which probes the tunnel from *inside* the container.

Trade-off worth knowing: any container on this host can use the proxy. That is fine here (the box only runs
FlareSolverr), but do not treat the bridge as a security boundary if that changes.

## Setup

### 1. Server: allow the client to choose the bind address

`-R 172.17.0.1:...` needs `GatewayPorts clientspecified`, which defaults to `no`. Scope it to a dedicated
account rather than enabling it globally.

**Append this to the END of `/etc/ssh/sshd_config`.** It must not go in `/etc/ssh/sshd_config.d/`: that
directory is pulled in by an `Include` near the *top* of the main file, and everything following a `Match`
belongs to that `Match` until the next one, so a `Match` block there would silently capture the rest of the
main config.

```
Match User modb-tunnel
    GatewayPorts clientspecified
    AllowTcpForwarding remote
    PermitListen 172.17.0.1:1080
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    PermitTunnel no
    ClientAliveInterval 30
    ClientAliveCountMax 3
```

`PermitListen` is the important one: it restricts this account to binding that single address:port and
nothing else. `ClientAlive*` makes sshd reap dead sessions, otherwise a stale listener keeps holding port
1080 and autossh's reconnect fails with `remote port forwarding failed for listen port 1080`.

Create the account with no shell (`-N` requests no command, so a shell is never needed), and restrict the key
to forwarding only in `/home/modb-tunnel/.ssh/authorized_keys`:

```
restrict,port-forwarding ssh-ed25519 AAAA...  modb-tunnel
```

`restrict` denies everything, then `port-forwarding` re-enables just what the tunnel needs.

Validate before reloading, and keep your current session open until you have confirmed a new one still works:

```
sudo sshd -t && sudo systemctl reload ssh
```

### 1b. Server: let the FlareSolverr container reach the tunnel

If the server runs `ufw` (or any host firewall), this step is **required** and its absence is easy to
misdiagnose. Traffic from a container to a *host port* traverses the `INPUT` chain, which ufw filters with a
default-deny policy. Docker's own rules only bypass ufw for `FORWARD` (published ports and
container-to-internet), so they do not cover this direction. The packets are dropped silently.

```
sudo ufw allow from 172.17.0.0/16 to 172.17.0.1 port 1080 proto tcp comment 'modb tunnel: FlareSolverr -> SOCKS'
```

Scoped to the bridge subnet, the bridge gateway, and one TCP port. It exposes nothing publicly: `172.17.0.1`
is not routable from the internet.

Symptom if missing: check 4 of `check-tunnel.sh` reports **timed out** (dropped) rather than **refused**, while
the host itself reaches the port fine. That difference is the whole diagnosis - "refused" means nothing is
listening (a bind problem), "timed out" means a firewall ate it.

### 2. Home: the SOCKS daemon

```
sudo apt install dante-server
sudo cp sockd.conf /etc/danted.conf
# set 'external:' to the internet-facing interface (ip route get 1.1.1.1)
sudo systemctl enable --now danted
```

**Then add this drop-in. It is not optional.** Dante binds its external address at startup and exits if the
address is not there yet, with no retry - so it loses any race against DHCP and stays dead until someone
notices. That is not hypothetical: it happened on 2026-10-14, almost certainly when a `dante-server` package
upgrade restarted the service before the interface had its lease, and the tunnel was silently down for two
days. The signature in `systemctl status danted` is a `Duration:` of a few tens of milliseconds plus:

```
warning: addrisbindable(): cannot bind address: <ip> ... Cannot assign requested address
error: checkconfig(): cannot bind external address #0: <ip>
alert: mother[1/1]: shutting down
```

```bash
sudo mkdir -p /etc/systemd/system/danted.service.d
printf '[Unit]\nWants=network-online.target\nAfter=network-online.target\n\n[Service]\nRestart=on-failure\nRestartSec=5\n' \
  | sudo tee /etc/systemd/system/danted.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl restart danted
systemctl status danted --no-pager | head -15
```

`network-online.target` waits for an actual address rather than just the network stack. `Restart=on-failure`
is the line that really matters: ordering alone is never fully reliable with DHCP, so the retry is what keeps
a lost race from becoming days of downtime. A drop-in is used rather than editing the shipped unit so a
package upgrade cannot discard it - and note that the upgrade which caused this also printed "The unit file
... changed on disk", so editing the unit directly would have been undone.

Diagnosing this from the crawl server is misleading in a specific way, so it is worth knowing the pattern:
`check-tunnel.sh` check 1 **passes** (the `ssh -R` listener is still bound on the server, and autossh keeps
reconnecting) and check 4 **passes** (the container can reach that listener), while only check 2 fails. A
bound port with no traffic through it means the far end of the forward is dead, not the tunnel itself.

### 3. Home: hold the tunnel up

```
sudo apt install autossh
ssh-keygen -t ed25519 -f ~/.ssh/modb_tunnel -C modb-tunnel
# replace HOME_USER / SERVER_USER / SERVER_HOST / SERVER_PORT in the unit first
sudo cp modb-tunnel.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now modb-tunnel
```

If sshd does not listen on 22 (check `Port` in the server's `sshd_config`), pass it explicitly. Note the
flags differ: **`ssh -p PORT`** but **`scp -P PORT`** (capital). The tunnel account has no shell, so install
the public key on the server side rather than with `ssh-copy-id`.

Test by hand before enabling the unit - it should connect and hang, which is success:

```
ssh -N -v -p SERVER_PORT \
    -o ExitOnForwardFailure=yes \
    -o IdentitiesOnly=yes \
    -i ~/.ssh/modb_tunnel \
    -R 172.17.0.1:1080:127.0.0.1:1080 \
    SERVER_USER@SERVER_HOST
```

### 4. Server: verify before trusting it

```
./scripts/tunnel/check-tunnel.sh
```

It checks the port is open, that the exit IP through the tunnel **differs** from the direct IP, that the
home network is **not** reachable through it, and that the FlareSolverr container can reach it too. Do not
enable the providers until this passes.

### 5. Server: turn it on

In `config.toml`:

```toml
[modb.app.tunnel]
enabled = true
# host = "172.17.0.1"   # docker bridge gateway (default)
# port = 1080           # default
# providersForTunnel = ["anime-planet.com", "anisearch.com", "simkl.com"]   # default (anidb uses the commercial proxy)
```

Then remove those three hostnames from `deactivatedMetaDataProviders`. No rebuild needed for either change.

If the tunnel is down when a run starts and any tunneled provider is active, the run **aborts before the
sudo prompt** rather than falling back to the banned datacenter IP. A run that does not crawl any tunneled
provider (for example `--only anilist`) skips the check entirely.

## Notes

* **Bandwidth.** Only the four providers use the tunnel; everything else keeps the direct datacenter path.
  anisearch alone is ~18.8k sequential entries, so expect a long, slow trickle over the home line rather
  than a burst.
* **DNS and IPv6.** `sockd.conf` only has IPv4 rules, and sockd denies when no rule matches, so an IPv6
  destination would be refused. In practice it never comes up, and it is worth knowing why, because all four
  provider(s) *do* publish AAAA records and this server *does* have IPv6:

  - the home machine is IPv4-only, so anything resolved there yields only A records;
  - the JVM defaults to `java.net.preferIPv6Addresses=false`, so anything resolved on the server prefers the
    A record too.

  Both paths therefore land on IPv4 regardless of which side performs the lookup, which is verified: the
  anisearch crawler (OkHttp -> `DefaultHttpClient(proxy = SOCKS)`) downloads real pages through the tunnel
  even though `anisearch.com` has an AAAA record. FlareSolverr uses `socks5://` and `check-tunnel.sh` uses
  `socks5h://`, both of which resolve at the proxy (i.e. at home).

  The latent risk is small but real: an IPv6-only provider, or someone setting
  `-Djava.net.preferIPv6Addresses=true`, would produce an IPv6 destination that sockd refuses. It fails
  closed (a refusal, never a leak to the banned datacenter path). Adding IPv6 rules would not help by itself
  since the home connection has no IPv6 to route it over.
* **Retries.** The anisearch crawler retries `ConnectException` by restarting the network interface. Once
  tunneled, that error more likely means the tunnel died, and a restart will not fix it (it drops the SSH
  connection and autossh reconnects). Harmless, but the retry is aimed at the wrong failure. See TODO.

## Commercial rotating proxy (anidb)

anidb enforces a **per-IP daily quota** (~100-200 requests/day), not a rate. A single residential exit - the
tunnel above, which is one home IP - cannot get past that quota, so anidb needs its traffic spread over a
pool of rotating residential IPs from a commercial provider.

That pool is not shared with the tunnel's FlareSolverr container. anidb gets a **dedicated second FlareSolverr
container** (default host port `8192`), pointed at the commercial proxy per session. Two reasons:

* **Parallelism.** FlareSolverr serializes on a single browser (`maxConcurrency = 1`). A second container has
  its own browser, so anidb's heavy initial crawl runs in parallel with the tunneled providers instead of
  queueing behind them.
* **Isolation.** If the commercial proxy flakes, only anidb's container is affected.

The tunnel set (`modb.app.tunnel.providersForTunnel`) and the commercial set
(`modb.app.proxy.commercial.providersForCommercialProxy`)
are **mutually exclusive** - a provider takes exactly one residential path. A hostname in both aborts the run
up front. While the commercial proxy is disabled, anidb must be turned off (see step 4), because it cannot run
on the datacenter IP without getting banned; the run aborts otherwise.

### Auth: whitelist or credentials

Both are supported, selected purely by whether credentials are present. Which one *works*, though, depends on
the proxy's scheme rather than on preference. From the FlareSolverr v3.5.0 source (`/app/utils.py`,
`get_webdriver`):

* **No credentials** - the URL is handed to Chromium as `--proxy-server=<url>`. Works for both `http://` and
  `socks5://`. This is how the reverse SSH tunnel already talks to dante.
* **With credentials** - FlareSolverr generates a throwaway MV3 extension which sets the proxy via
  `chrome.proxy` `fixed_servers` and answers the challenge from a `chrome.webRequest.onAuthRequired` listener.
  Proxy auth *is* properly implemented, and FlareSolverr runs under Xvfb rather than true headless, so the
  extension does load.

The catch is that `onAuthRequired` fires for an HTTP **407 Proxy Authentication Required** response. SOCKS5
authenticates inside the SOCKS protocol itself (RFC 1929), during the binary handshake at TCP connect time,
before any HTTP exists. Chromium has never implemented SOCKS5 username/password auth - it only offers the
"no authentication" method, so a proxy demanding credentials replies `0xFF` ("no acceptable methods") and
closes the connection. There is no request and no 407 for the listener to attach to.

| | credentials | IP whitelisting |
| --- | --- | --- |
| `http(s)://` gateway | works (extension answers the 407) | works |
| `socks5://` gateway | **fails** - see below | works |

The `socks5://` + credentials case surfaces as a connection error (`ERR_SOCKS_CONNECTION_FAILED`), **not** as
a credentials error, which makes it easy to misdiagnose.

A `socks5://` + credentials provider is still usable, just not directly. Three ways out, in order of
preference:

1. **IP whitelisting.** Register this server's IP with the provider and leave the credentials blank. Simplest,
   and keeps no secrets on disk.
2. **Use the provider's `http://` endpoint.** Most residential providers expose both HTTP and SOCKS5 on
   different ports, and credentials work fine over HTTP. Usually a one-line change - check this first.
3. **Run a local authless shim** which takes an authless connection from FlareSolverr and adds the credentials
   upstream - architecturally identical to what dante already does for the tunnel:

   ```bash
   gost -L socks5://172.17.0.1:1081 -F socks5://user:pass@gateway.example.com:7000
   ```

   Then point `url` at `socks5://172.17.0.1:1081` and leave `username`/`password` blank. Chromium sees an
   authless SOCKS5 proxy and the shim speaks RFC 1929 on its behalf. Bind it to the docker bridge gateway
   rather than `127.0.0.1`, for the same reason the tunnel does.

**`check-commercial-proxy.sh` cannot catch this.** It probes with curl, which implements RFC 1929 itself, so a
`socks5://` + credentials setup authenticates fine under curl and still fails inside FlareSolverr. Check 4
only confirms the container is running, not that the browser can authenticate through the proxy.

### Terminology

The vocabulary here is not standardised, and vendors, blogs and LLMs each use different words for the same
two or three things. What this repository means by them:

| term used here | definition |
| --- | --- |
| **sticky session** | the provider holding one exit IP for you until a label or timer expires |
| **session ID** | an arbitrary label you invent, which the provider maps to an exit IP |
| **session rotation** | our client-side pattern of discarding a session on purpose to draw a new IP |

**Vendor-canonical names**, i.e. what actually finds the right documentation page:

* **sticky session** / **sticky IP** - universal across all five providers
* **session ID** / **session parameter** / **session token** - the label
* **session control** - Oxylabs' heading for the whole feature
  ([Session Control & IP Rotation](https://developers.oxylabs.io/products/proxies/residential-proxies/session-control))
* **username parameters** / **authentication parameters** - how the label is transmitted
* **session duration** / **session lifetime** / **session TTL** - how long a sticky IP is held
* **rotating vs sticky sessions** - the two modes

**Colloquial aliases** you will hear, none of which appear verbatim in vendor documentation. Recorded here
only so they are recognisable, not recommended for searching:

* *dynamic session steering*
* *dynamic session control*
* *parameter steering* / *credential parameter steering*

Note that **dynamic** is a legitimate term in this space, but it describes the *product category* - a
"dynamic residential proxy" is a rotating residential proxy, as opposed to a static/ISP one. It is not part
of the name of the session mechanism, which is where the confusion comes from. The accurate long-form
description of this setup is: *a dynamic (rotating) residential proxy, using sticky sessions with a session
ID that we rotate per batch.*

### Session rotation: how a fresh exit IP is actually obtained

A rotating pool does nothing on its own. **The provider cannot tell that a new FlareSolverr session has
begun**, because every session presents the same credentials, so it keeps serving whatever exit IP that
credential string is bound to. Destroying and recreating the session changes nothing by itself.

Rotation has to be expressed **in the credentials**. Put the literal token `{session}` wherever the provider
documents its own sticky-session parameter, and a fresh random 8-character alphanumeric id is substituted
each time a session is created. Each new session then looks like a new client and is given a new residential
IP. The id is 8 alphanumerics because that is the intersection of what the providers accept - IPRoyal
requires exactly 8, the others take anything.

The parameter's spelling differs per provider:

| provider | where the parameter goes |
| --- | --- |
| IPRoyal | the **password**: `REALPASS_session-{session}_lifetime-30m` |
| Decodo | the **username**: `user-REALUSER-session-{session}` |
| Webshare / DataImpulse | username-parameter style; confirm the spelling in their dashboard |

`rotateAfterRequests` (default `50`) is how many requests one session serves before being recycled. It
trades the two failure modes against each other:

* **Too high** and the batch burns through anidb's per-IP daily quota (~100-200).
* **Too low** and every short-lived session pays the full Cloudflare solve again, ~19s against ~1.5s on a
  reused session. Rotating per request would cost roughly 77 hours across anidb's 14,517 entries.

`0` disables rotation. Only commercially-routed provider(s) are recycled - a tunneled provider never is,
since the tunnel has one home IP and recycling would discard a valid Cloudflare clearance for nothing.

A mismatched configuration is not fatal but logs a WARN at startup: `rotateAfterRequests` set with no
`{session}` in the credentials, or `{session}` present with `rotateAfterRequests = 0`. Watch for those on
the first run, because the failure they describe - a whole seed crawled from one IP - is expensive to notice
late.

### Setup

1. **Start the second container** (or let the app start it on the next run - it reuses one already running,
   matched by published port):

   ```bash
   sudo docker run -p 127.0.0.1:8192:8191 -d --restart unless-stopped \
     --name flaresolverr-commercial ghcr.io/flaresolverr/flaresolverr:v3.5.2
   ```

   No proxy settings go on the container - the proxy is passed per session by the app, so this container is
   identical to the tunnel one except its host port and name.

2. **Configure** `config.toml`. The `url`/`username`/`password` are secret, so they belong only in the
   gitignored `config.toml`, never in source:

   ```toml
   [modb.app.proxy.commercial]
   enabled = true
   # flaresolverrPort = 8192          # host port of the dedicated container (default)
   # providersForCommercialProxy = ["anidb.net"]   # default
   url = "socks5://gateway.example.com:7000"
   # username = "..."   # optional - set together with password for auth
   # password = "..."   # optional - leave both blank for IP whitelisting
   ```

3. **Verify** before enabling, passing the same proxy values through the environment:

   ```bash
   COMMERCIAL_PROXY_URL="socks5://gateway.example.com:7000" \
     ./scripts/tunnel/check-commercial-proxy.sh
   # add COMMERCIAL_PROXY_USER / COMMERCIAL_PROXY_PASS if the provider needs auth
   ```

4. **Turn anidb on.** While the commercial proxy is off, keep anidb deactivated via the dedicated global key,
   so the guard is satisfied and anidb never runs on the datacenter IP:

   ```toml
   deactivatedCommercialMetaDataProviders = ["anidb.net"]   # anidb off until the pool is ready
   ```

   A provider listed there does not crawl at all. To run anidb through the pool, enable the proxy (step 2) and
   remove `anidb.net` from that list. (The general `deactivatedMetaDataProviders` and `--skip anidb` also work
   as off switches.) No rebuild needed for the config changes.

The run **aborts up front** on a misconfiguration rather than starting anidb against a broken or missing proxy:
an enabled proxy with a blank `url` or only one of `username`/`password`; a provider in both the tunnel and
commercial sets; or a commercial provider left active while the proxy is disabled.
