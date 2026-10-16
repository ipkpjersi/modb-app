#!/usr/bin/env bash
#
# Verifies the commercial rotating residential proxy used by the dedicated second FlareSolverr container.
#
# Checks, in order:
#   1. the commercial proxy config is present (URL required; credentials optional for IP whitelisting)
#   2. a request through the proxy exits from a different IP than the direct datacenter path
#   3. a second request rotates to another IP (informational - expected for a rotating pool)
#   4. the dedicated FlareSolverr container is running on the commercial port
#
# Run this before enabling modb.app.proxy.commercial and re-activating anidb, and any time an anidb crawl
# fails with a proxy error. This is the commercial-path counterpart of check-tunnel.sh.
#
# KNOWN BLIND SPOT: these checks probe with curl, which implements SOCKS5 username/password auth (RFC 1929).
# Chromium does not, so a socks5:// endpoint WITH credentials passes every check here and still fails inside
# FlareSolverr, as a connection error rather than an auth error. Check 4 only confirms the container is
# running, not that its browser can authenticate through the proxy. See README.md - use IP whitelisting, the
# provider's http:// endpoint, or a local authless shim for that case.
#
# Config comes from the environment (these are secret, keep them out of the repo):
#   COMMERCIAL_PROXY_URL          e.g. socks5://gateway.example.com:7000  (required)
#   COMMERCIAL_PROXY_USER         proxy username    (optional - leave blank for IP whitelisting)
#   COMMERCIAL_PROXY_PASS         proxy password    (optional - leave blank for IP whitelisting)
#   FLARESOLVERR_COMMERCIAL_PORT  host port of the commercial container (default 8192)

set -uo pipefail

PROXY_URL="${COMMERCIAL_PROXY_URL:-}"
PROXY_USER="${COMMERCIAL_PROXY_USER:-}"
PROXY_PASS="${COMMERCIAL_PROXY_PASS:-}"
FLARESOLVERR_COMMERCIAL_PORT="${FLARESOLVERR_COMMERCIAL_PORT:-8192}"
FLARESOLVERR_IMAGE_MATCH="flaresolverr"
IP_SERVICE="https://api.ipify.org"

failures=0
fail() { echo "  FAIL: $*"; failures=$((failures + 1)); }
pass() { echo "  ok: $*"; }

# curl proxy args, including auth only when BOTH username and password are set.
proxy_args=(--proxy "$PROXY_URL")
if [ -n "$PROXY_USER" ] && [ -n "$PROXY_PASS" ]; then
    proxy_args+=(--proxy-user "${PROXY_USER}:${PROXY_PASS}")
fi

echo "== 1. commercial proxy config =="
if [ -z "$PROXY_URL" ]; then
    fail "COMMERCIAL_PROXY_URL is not set - export it (and COMMERCIAL_PROXY_USER/PASS if the provider needs auth)."
    echo
    echo "Cannot continue without a proxy URL."
    exit 1
fi
if { [ -n "$PROXY_USER" ] && [ -z "$PROXY_PASS" ]; } || { [ -z "$PROXY_USER" ] && [ -n "$PROXY_PASS" ]; }; then
    fail "only one of COMMERCIAL_PROXY_USER / COMMERCIAL_PROXY_PASS is set - set both (auth) or neither (IP whitelisting)."
elif [ -n "$PROXY_USER" ]; then
    pass "proxy URL set, using username/password auth"
else
    pass "proxy URL set, no credentials (IP whitelisting)"
fi

echo "== 2. exit IP differs from the direct datacenter path =="
direct_ip="$(curl -s --max-time 20 "$IP_SERVICE" || true)"
proxy_ip="$(curl -s --max-time 30 "${proxy_args[@]}" "$IP_SERVICE" || true)"
if [ -z "$proxy_ip" ]; then
    fail "no response through the commercial proxy - check the URL, the credentials, or that this server's IP is whitelisted with the provider."
elif [ "$proxy_ip" = "$direct_ip" ]; then
    fail "proxy exit IP [$proxy_ip] equals the direct IP - traffic is NOT going through the proxy."
else
    pass "direct=${direct_ip}  proxy=${proxy_ip} (different, as expected)"
fi

echo "== 3. rotation (informational) =="
proxy_ip2="$(curl -s --max-time 30 "${proxy_args[@]}" "$IP_SERVICE" || true)"
if [ -n "$proxy_ip2" ] && [ "$proxy_ip2" != "$proxy_ip" ]; then
    pass "second request rotated to ${proxy_ip2} (rotating pool)"
elif [ -n "$proxy_ip2" ]; then
    echo "  note: second request reused ${proxy_ip2} - fine for a sticky session, but a rotating endpoint would change"
else
    echo "  note: second request returned nothing - not fatal, but worth a look"
fi

echo "== 4. dedicated FlareSolverr container on port ${FLARESOLVERR_COMMERCIAL_PORT} =="
# Match by the published host port, so this finds the commercial container and not the tunnel one. The
# published mapping shows as '127.0.0.1:8192->8191/tcp'.
container="$(docker ps --filter "ancestor=${FLARESOLVERR_IMAGE_MATCH}" --format '{{.ID}} {{.Ports}}' 2>/dev/null | grep ":${FLARESOLVERR_COMMERCIAL_PORT}->" | awk '{print $1}' | head -1)"
# 'ancestor' needs the image reference to match, and the real one is fully qualified
# (ghcr.io/flaresolverr/flaresolverr:v3.5.2), so the filter above finds nothing in practice. Fall back to
# matching the image column, exactly as check-tunnel.sh does.
if [ -z "$container" ]; then
    container="$(docker ps --format '{{.ID}} {{.Image}} {{.Ports}}' 2>/dev/null | grep -i "$FLARESOLVERR_IMAGE_MATCH" | grep ":${FLARESOLVERR_COMMERCIAL_PORT}->" | awk '{print $1}' | head -1)"
fi
if [ -z "$container" ]; then
    fail "no FlareSolverr container publishing port ${FLARESOLVERR_COMMERCIAL_PORT} - start it (see README.md) or let the app start it on the next run."
else
    pass "container [$container] is running on port ${FLARESOLVERR_COMMERCIAL_PORT}"
fi

echo
if [ "$failures" -eq 0 ]; then
    echo "All checks passed. Safe to enable modb.app.proxy.commercial and re-activate anidb."
    # curl authenticates to SOCKS5 where Chromium cannot, so warn on the one combination these checks
    # cannot actually validate rather than letting the all-clear above imply it was tested.
    if [ -n "$PROXY_USER" ] && case "$PROXY_URL" in socks5*|socks4*) true;; *) false;; esac; then
        echo
        echo "WARNING: this is a SOCKS proxy WITH credentials. curl authenticated, but Chromium does not"
        echo "         implement SOCKS5 user/password auth, so FlareSolverr will still fail. Use IP"
        echo "         whitelisting, the provider's http:// endpoint, or a local authless shim (README.md)."
    fi
    exit 0
fi
echo "${failures} check(s) failed."
exit 1
