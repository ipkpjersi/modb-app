#!/usr/bin/env bash
#
# Verifies the reverse SSH tunnel from the CRAWL SERVER side.
#
# Checks, in order:
#   1. the forwarded port is open
#   2. traffic through it exits from a different (residential) IP than the direct path
#   3. the deny rules on the home SOCKS daemon actually block the home network
#   4. the tunnel is reachable from inside the FlareSolverr container, not just from the host
#
# Run this before re-enabling the tunneled providers, and any time a crawl fails with a tunnel error.

set -uo pipefail

TUNNEL_HOST="${TUNNEL_HOST:-172.17.0.1}"
TUNNEL_PORT="${TUNNEL_PORT:-1080}"
PROXY="socks5h://${TUNNEL_HOST}:${TUNNEL_PORT}"
FLARESOLVERR_IMAGE_MATCH="flaresolverr"

failures=0

fail() { echo "  FAIL: $*"; failures=$((failures + 1)); }
pass() { echo "  ok: $*"; }

echo "== 1. tunnel port ${TUNNEL_HOST}:${TUNNEL_PORT} =="
if timeout 5 bash -c "cat < /dev/null > /dev/tcp/${TUNNEL_HOST}/${TUNNEL_PORT}" 2>/dev/null; then
    pass "port is open"
else
    fail "port is not open - the tunnel is down. Start modb-tunnel.service on the home machine."
    echo
    echo "Tunnel is down; skipping the remaining checks."
    exit 1
fi

echo "== 2. exit IP =="
direct_ip="$(curl -s --max-time 15 https://api.ipify.org || true)"
tunnel_ip="$(curl -s --max-time 20 --proxy "$PROXY" https://api.ipify.org || true)"

if [ -z "$tunnel_ip" ]; then
    fail "no response through the tunnel - the SOCKS daemon on the home side may be down"
elif [ "$tunnel_ip" = "$direct_ip" ]; then
    fail "tunnel exit IP equals the direct IP ($direct_ip) - traffic is NOT leaving via the home connection"
else
    pass "direct=${direct_ip}  tunnel=${tunnel_ip} (different, as expected)"
fi

echo "== 3. home network is not reachable through the tunnel =="
# These must be refused by the sockd deny rules. A success here means the crawl server can reach into the
# home LAN, which is exactly what the ACL exists to prevent.
#
# The defaults cover LAN gateways, point-to-point links between home machines, and loopback. All sit inside
# the RFC1918 ranges sockd.conf blocks (192.168.0.0/16, 172.16.0.0/12, 127.0.0.0/8).
#
# A target that actually exists on the home network is the valuable kind: if the deny rules were ever removed,
# curl would reach it, return content, and turn this check red. A target that does not exist is refused either
# way, so it broadens range coverage but proves nothing about the rules firing. Keep at least one real one.
#
# Override for a different home network:  DENY_TARGETS="http://10.0.0.1/ ..." ./check-tunnel.sh
DENY_TARGETS="${DENY_TARGETS:-http://192.168.1.1/ http://192.168.2.1/ http://192.168.3.1/ http://172.16.1.1/ http://172.16.1.2/ http://127.0.0.1:22/}"

for target in $DENY_TARGETS; do
    if curl -s --max-time 8 --proxy "$PROXY" "$target" >/dev/null 2>&1; then
        fail "reached [$target] through the tunnel - sockd deny rules are NOT working"
    else
        pass "[$target] refused"
    fi
done

echo "== 4. reachable from the FlareSolverr container =="
container="$(docker ps --filter "ancestor=${FLARESOLVERR_IMAGE_MATCH}" --format '{{.ID}}' 2>/dev/null | head -1)"
if [ -z "$container" ]; then
    container="$(docker ps --format '{{.ID}} {{.Image}}' 2>/dev/null | grep -i "$FLARESOLVERR_IMAGE_MATCH" | awk '{print $1}' | head -1)"
fi

if [ -z "$container" ]; then
    echo "  skipped: no FlareSolverr container running (start a crawl, or 'docker run' it, then re-check)"
else
    # python3 ships with the FlareSolverr image and reports WHY it failed, which matters here: "refused"
    # and "timed out" have completely different causes (see below).
    result="$(docker exec "$container" python3 -c "
import socket
try:
    socket.create_connection(('${TUNNEL_HOST}', ${TUNNEL_PORT}), 5).close()
    print('ok')
except Exception as e:
    print(type(e).__name__)
" 2>&1 | tail -1)"

    case "$result" in
        ok)
            pass "container [$container] can reach the tunnel"
            ;;
        TimeoutError)
            # Packets left but nothing came back: they are being dropped, not refused. On this host that is
            # ufw. Container -> host-port traffic traverses the INPUT chain, which ufw filters with a
            # default-deny policy; docker's own rules only bypass ufw for FORWARD (published ports and
            # container -> internet), so they do not help here.
            fail "container [$container] TIMED OUT reaching ${TUNNEL_HOST}:${TUNNEL_PORT} - packets are being DROPPED by a firewall, not refused. Fix: sudo ufw allow from 172.17.0.0/16 to ${TUNNEL_HOST} port ${TUNNEL_PORT} proto tcp"
            ;;
        ConnectionRefusedError)
            # Nothing is listening on that address from the container's point of view.
            fail "container [$container] was REFUSED at ${TUNNEL_HOST}:${TUNNEL_PORT} - nothing is listening there. Is the -R bound to the docker bridge gateway rather than 127.0.0.1? Check: ss -tlnp | grep ${TUNNEL_PORT}"
            ;;
        *)
            fail "container [$container] cannot reach ${TUNNEL_HOST}:${TUNNEL_PORT} - [$result]"
            ;;
    esac
fi

echo
if [ "$failures" -eq 0 ]; then
    echo "All checks passed. Safe to enable modb.app.tunnel.enabled and re-activate the providers."
    exit 0
fi

echo "${failures} check(s) failed."
exit 1
