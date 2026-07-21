# Reverse SSH tunnel (residential exit IP)

`anidb.net`, `anime-planet.com`, `simkl.com` and `anisearch.com` ban this host's datacenter IP range
outright. FlareSolverr solves their Cloudflare challenge but cannot change the exit IP, and IPv6 rotation
only shuffles addresses within the same datacenter /64. They need traffic to leave via a **residential**
connection.

## How it works

```
  HOME (residential)                                CRAWL SERVER (datacenter)
  ------------------                                -------------------------
  danted on 127.0.0.1:1080  <---- ssh -R ------  listens on 172.17.0.1:1080
        |                     (home dials out)          ^              ^
        v                                               |              |
   the internet                                  modb-app        FlareSolverr
   (residential IP)                              (anisearch)     (anidb, anime-planet, simkl)
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
anidb/anime-planet/simkl request would fail with a proxy connection error (it does not fall back to a direct
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
# providers = ["anidb.net", "anime-planet.com", "anisearch.com", "simkl.com"]   # default
```

Then remove those four hostnames from `deactivatedMetaDataProviders`. No rebuild needed for either change.

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
