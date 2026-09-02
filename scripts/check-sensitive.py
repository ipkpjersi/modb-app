#!/usr/bin/env python3
"""
Scan for host-specific or sensitive values before they reach a commit.

Why this exists
---------------
This repository is public, but it is operated from one specific host, and the operational notes
(TODO.md, scripts/tunnel/) are written from that host's point of view. That combination makes it easy
to paste a real exit IP, a home path or a webhook into a doc while writing up an investigation.

What counts as a sensitive IP
-----------------------------
Only THIS HOST'S addresses, not every routable address. The addresses and routed prefixes are read
live from 'ip addr' and 'ip route' each run, so nothing is hardcoded and the check follows the host if
it is renumbered. An address is reported when it equals one of this host's addresses, or falls inside
one of its routed prefixes.

Addresses which are ours but are NOT visible on this host - above all the residential exit IP that the
reverse SSH tunnel comes from - cannot be discovered that way. Add those to an extras file kept
OUTSIDE the repository so it can never itself be committed:

    ~/.modb-sensitive-extra        one value per line, '#' comments allowed
    MODB_SENSITIVE_EXTRA           environment variable, comma separated

The same file holds any NAME that must never be committed, as 'name:<value>' lines. Names are kept
there rather than in this script for the obvious reason: a word that must not appear in the repository
cannot be hardcoded into a tracked file in order to search for it.

Third-party addresses stay unreported by design: anisearch's IP in TODO.md item 6 and the addresses
inside scraped test fixtures are not ours to protect and would only make the check too noisy to keep.

Scope
-----
By default only the STAGED ADDED LINES are scanned. That keeps the hook quiet about legitimate content
already in the tree (the codecov badge token, the ipkpjersi repo URLs the build and badges depend on),
so it only ever complains about something being newly introduced.

Usage
-----
  ./check-sensitive.py                    # scan staged added lines (what the pre-commit hook runs)
  ./check-sensitive.py --all              # scan every tracked file, for an audit
  ./check-sensitive.py --show-identity    # print what it considers "mine", then exit
  ./check-sensitive.py FILE [FILE ...]    # scan specific files

Exit status is 0 when nothing is found and 1 otherwise, so it can gate a commit. To commit anyway:

  git commit --no-verify

To allow one specific line forever, put 'allow-sensitive' in a comment on that line.
"""

import argparse
import ipaddress
import os
import pathlib
import re
import subprocess
import sys

ALLOW_MARKER = "allow-sensitive"
EXTRA_FILE = pathlib.Path.home() / ".modb-sensitive-extra"
EXTRA_ENV = "MODB_SENSITIVE_EXTRA"

# This file necessarily contains the very patterns it searches for, so it never scans itself.
SELF = "scripts/check-sensitive.py"

# Test resources are whole pages scraped from the metadata providers. They legitimately contain
# third-party IPs, addresses and tokens, so scanning them says nothing about what WE might leak.
FIXTURE_PATH = re.compile(r"src/test/resources/")

# Hosts and values that are part of the project's public identity or are documented placeholders.
ALLOWED_SUBSTRINGS = (
    "gateway.example.com",
    "/home/HOME_USER",
    "/home/modb-tunnel",
)

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b(?:/\d{1,2})?")
IPV6 = re.compile(r"\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,}(?:/\d{1,3})?")
EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]*[a-zA-Z]{2,}\b")
HOME_PATH = re.compile(r"/home/[a-z_][a-z0-9_-]*")
PRIVATE_KEY = re.compile(r"BEGIN\s+(?:OPENSSH|RSA|EC|DSA|PGP)\s+PRIVATE KEY")
SSH_PUBKEY = re.compile(r"ssh-(?:rsa|ed25519|dss)\s+AAAA[A-Za-z0-9+/]{20,}")
WEBHOOK = re.compile(r"discord(?:app)?\.com/api/webhooks/\d+")

# Only commit-authorship attribution, not every mention of the tool: setup-server.sh documents
# 'claude setup-token' perfectly legitimately.
ATTRIBUTION = re.compile(r"\bco-authored-by\b|generated with \[|\bclaude-session\b", re.IGNORECASE)

# git@github.com is an SSH remote, not a contact address, and appears in every build.gradle.kts.
ALLOWED_EMAILS = re.compile(r"^(git@github\.com|noreply@|.*@example\.(com|org|net)$)", re.IGNORECASE)

SECRET_ASSIGN = re.compile(
    r"""(?ix)
    \b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\b
    \s*[=:]\s*
    ["']([^"']{8,})["']
    """
)
SECRET_PLACEHOLDER = re.compile(
    r"^[\s.<$*]|\.\.\.|example|placeholder|changeme|dummy|redacted|your[_-]|xxx|\$\{", re.IGNORECASE
)

FORBIDDEN_PATHS = re.compile(
    r"(^|/)(authorized_keys|known_hosts|id_rsa|id_ed25519|id_dsa|\.env|credentials)$"
    r"|\.(pem|key|p12|pfx|jks|keystore)$"
)


def _run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def my_identity():
    """Return (addresses, networks, literals) belonging to this host, read live from iproute2 plus the extras.

    Loopback, link-local and private ranges are skipped: they are not identifying, and 127.0.0.1 and
    172.17.0.1 appear throughout the tunnel documentation on purpose.
    """
    addresses = set()
    networks = []

    for line in _run(["ip", "-o", "addr", "show"]).splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[2] not in ("inet", "inet6"):
            continue
        try:
            interface = ipaddress.ip_interface(fields[3])
        except ValueError:
            continue
        addr = interface.ip
        if not addr.is_global:
            continue
        addresses.add(str(addr))
        # A /32 or /128 says nothing about a prefix, so only keep genuinely routed ranges.
        if interface.network.prefixlen < interface.network.max_prefixlen:
            networks.append(interface.network)

    for line in _run(["ip", "route", "show", "default"]).splitlines():
        match = re.search(r"via\s+(\S+)", line)
        if match:
            try:
                if ipaddress.ip_address(match.group(1)).is_global:
                    addresses.add(match.group(1))
            except ValueError:
                pass

    raw_extras = []
    if EXTRA_FILE.is_file():
        raw_extras.extend(EXTRA_FILE.read_text().splitlines())
    raw_extras.extend(os.environ.get(EXTRA_ENV, "").split(","))

    literals = set()
    for entry in raw_extras:
        entry = entry.split("#", 1)[0].strip()
        if not entry:
            continue
        # 'name:<value>' opts a literal string in, for anything that is not an address.
        if entry.lower().startswith("name:"):
            value = entry[5:].strip()
            if value:
                literals.add(value)
            continue
        try:
            network = ipaddress.ip_network(entry, strict=False)
        except ValueError:
            continue
        if network.prefixlen == network.max_prefixlen:
            addresses.add(str(network.network_address))
        else:
            networks.append(network)

    return addresses, networks, literals


def my_usernames():
    """Local login name(s) to look for. Derived from the host, like the addresses.

    Only the invoking user. Service accounts are deliberately excluded: 'modb-tunnel' is documented on
    purpose in scripts/tunnel/README.md, so treating it as secret would fight the documentation.
    """
    who = _run(["whoami"]).strip()
    return {who} if who else set()


def username_contexts(names):
    """Compile a context-scoped matcher per username.

    A bare word match is unusable here: 'ken' occurs 49 times in tracked files as anime data - studio
    names like 'ken groove' and titles in review-data. Only positions that actually denote an ACCOUNT
    are reported, which is where a real leak would be.
    """
    patterns = []
    for name in names:
        escaped = re.escape(name)
        patterns.append((name, re.compile(
            r"{0}@"                                            # ssh/scp target, user@host
            r"|\b(?:-u|-l|--user|--username)[=\s]+{0}\b"       # command line flags
            r"|\bUser\s*[=:]\s*{0}\b"                          # sshd_config, systemd unit
            r"|\b(?:ssh|su|sudo\s+-u|login|runuser)\s+{0}\b"   # invocations
            r"|\buser\s+{0}\b"                                  # prose: "the user ken"
            r"|/home/{0}\b|~{0}\b".format(escaped), re.IGNORECASE)))
    return patterns


def is_mine(text, addresses, networks):
    """True when an address literal belongs to this host or to the configured extras."""
    bare = text.split("/", 1)[0]
    if bare in addresses:
        return True
    try:
        addr = ipaddress.ip_address(bare)
    except ValueError:
        return False
    return any(addr in network for network in networks)


def scan_line(path, lineno, line, addresses, networks, usernames=(), literals=()):
    """Return a list of (path, lineno, rule, detail) findings for one line."""
    if ALLOW_MARKER in line:
        return []
    if path and path.endswith(SELF):
        return []

    haystack = line
    for allowed in ALLOWED_SUBSTRINGS:
        haystack = haystack.replace(allowed, "")

    findings = []

    def report(rule, detail):
        findings.append((path, lineno, rule, detail))

    if PRIVATE_KEY.search(haystack):
        report("private key", "private key material")
    if SSH_PUBKEY.search(haystack):
        report("ssh key", "ssh public key body")
    if WEBHOOK.search(haystack):
        report("webhook", "live webhook URL (use <id>/<token>)")
    if ATTRIBUTION.search(haystack):
        report("attribution", "commit co-author attribution")

    for pattern in (IPV4, IPV6):
        for match in pattern.finditer(haystack):
            if is_mine(match.group(), addresses, networks):
                report("own ip", match.group())

    for match in EMAIL.finditer(haystack):
        if not ALLOWED_EMAILS.match(match.group()):
            report("email", match.group())

    for match in HOME_PATH.finditer(haystack):
        report("home path", match.group())

    for name, pattern in usernames:
        if pattern.search(haystack):
            report("username", "account name '{}' in an identity position".format(name))

    for literal in literals:
        if literal.lower() in haystack.lower():
            report("literal", literal)

    for match in SECRET_ASSIGN.finditer(haystack):
        if not SECRET_PLACEHOLDER.search(match.group(2)):
            report("secret", "{}=<literal value>".format(match.group(1)))

    return findings


def staged_added_lines():
    """Yield (path, lineno, line) for every line added by the staged changes."""
    diff = _run(["git", "diff", "--cached", "--unified=0", "--diff-filter=ACMR"])
    path = None
    lineno = 0
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("@@"):
            match = re.search(r"\+(\d+)", line)
            lineno = int(match.group(1)) if match else 0
        elif line.startswith("+") and not line.startswith("+++"):
            yield path, str(lineno), line[1:]
            lineno += 1


def tracked_files(include_fixtures=False):
    paths = [p for p in _run(["git", "ls-files"]).splitlines() if p]
    if include_fixtures:
        return paths
    return [p for p in paths if not FIXTURE_PATH.search(p)]


def staged_paths():
    return [p for p in _run(["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"]).splitlines() if p]


def file_lines(paths):
    for path in paths:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                for number, line in enumerate(handle, start=1):
                    yield path, str(number), line.rstrip("\n")
        except (IsADirectoryError, FileNotFoundError, PermissionError):
            continue


def main():
    parser = argparse.ArgumentParser(description="Scan for sensitive or host-specific values.")
    parser.add_argument("files", nargs="*", help="specific files to scan (default: staged changes)")
    parser.add_argument("--all", action="store_true", help="scan every tracked file instead of the staged diff")
    parser.add_argument("--include-fixtures", action="store_true",
                        help="with --all, also scan src/test/resources (scraped pages, very noisy)")
    parser.add_argument("--label", metavar="TEXT",
                        help="describe the scanned input in messages, e.g. 'commit message'")
    parser.add_argument("--show-identity", action="store_true",
                        help="print the addresses and prefixes treated as this host's, then exit")
    args = parser.parse_args()

    addresses, networks, literals = my_identity()
    usernames = username_contexts(my_usernames())

    if args.show_identity:
        print("addresses treated as mine:")
        for addr in sorted(addresses):
            print("  {}".format(addr))
        print("prefixes treated as mine:")
        for network in networks:
            print("  {}".format(network))
        print("account names treated as mine (context scoped):")
        for name, _ in usernames:
            print("  {}".format(name))
        print("extra literals opted in via 'name:':")
        for literal in sorted(literals) or ["  (none)"]:
            print("  {}".format(literal) if literals else literal)
        print("extras file: {} ({})".format(EXTRA_FILE, "present" if EXTRA_FILE.is_file() else "absent"))
        return 0

    if not addresses and not networks:
        print("check-sensitive: warning: could not determine this host's addresses; IP rules are inactive.",
              file=sys.stderr)

    if args.all:
        paths_to_check = tracked_files(include_fixtures=args.include_fixtures)
        source = file_lines(paths_to_check)
        scope = "all tracked files"
    elif args.files:
        paths_to_check = args.files
        source = file_lines(args.files)
        scope = args.label or "{} file(s)".format(len(args.files))
    else:
        paths_to_check = staged_paths()
        source = staged_added_lines()
        scope = "staged changes"

    findings = []
    for path, lineno, line in source:
        findings.extend(scan_line(path, lineno, line, addresses, networks, usernames, literals))

    for path in paths_to_check:
        if FORBIDDEN_PATHS.search(path):
            findings.append((path, "-", "forbidden file", "credential/key file must not be tracked"))

    if not findings:
        print("check-sensitive: no findings in {}.".format(scope))
        return 0

    print("check-sensitive: {} finding(s) in {}:".format(len(findings), scope), file=sys.stderr)
    print("", file=sys.stderr)
    for path, lineno, rule, detail in findings:
        print("  {}:{}: [{}] {}".format(path, lineno, rule, detail), file=sys.stderr)
    print("", file=sys.stderr)
    print("If a finding is intentional, add '{}' in a comment on that line,".format(ALLOW_MARKER), file=sys.stderr)
    print("or bypass the whole check once with: git commit --no-verify", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
