#!/usr/bin/env bash
#
# Set up a Linux server (Ubuntu/Debian) as a modb-app crawl host.
#
# Installs everything the build and crawl need:
#   - base tools: git, make, curl, unzip, tmux, iproute2 (provides `ip`)
#   - GitHub CLI (gh)
#   - JDK 25 (Temurin) into /opt/jdk-25, and points Gradle at it WITHOUT changing
#     the system default `java`
#   - sourcemeta jsonschema (used by the anime-offline-database makefile validation)
#   - Claude Code
#
# Gradle itself is provided by the repo wrapper (./gradlew) - no separate install.
# No web browser / headless Chromium is required: the crawlers use plain HTTP.
#
# This script contains NO secrets and NO host-specific values (tokens, IP prefixes,
# directories). Those are set up separately - see the checklist it prints at the end.
#
# Run as a normal user that has sudo access:
#   ./scripts/setup-server.sh
#
# Overridable via environment variables:
#   JDK_URL, JDK_DIR, JSONSCHEMA_VERSION, GITHUB_OWNER

set -euo pipefail

JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse}"
JDK_DIR="${JDK_DIR:-/opt/jdk-25}"
JSONSCHEMA_VERSION="${JSONSCHEMA_VERSION:-16.0.0}"
GITHUB_OWNER="${GITHUB_OWNER:-ipkpjersi}"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

log "Installing base packages"
sudo apt-get update
sudo apt-get install -y git make curl unzip tmux iproute2 ca-certificates

log "Installing GitHub CLI (gh)"
if ! command -v gh >/dev/null 2>&1; then
    sudo mkdir -p -m 755 /etc/apt/keyrings
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
    sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        | sudo tee /etc/apt/sources.list.d/github-cli.list >/dev/null
    sudo apt-get update
    sudo apt-get install -y gh
fi
gh --version | head -1

log "Installing JDK 25 into $JDK_DIR"
if [ ! -x "$JDK_DIR/bin/java" ]; then
    curl -fsSL "$JDK_URL" -o /tmp/jdk25.tar.gz
    sudo mkdir -p "$JDK_DIR"
    sudo tar -xzf /tmp/jdk25.tar.gz -C "$JDK_DIR" --strip-components=1
    rm -f /tmp/jdk25.tar.gz
fi
"$JDK_DIR/bin/java" -version

log "Pointing Gradle at JDK 25 (system default java is left untouched)"
mkdir -p "$HOME/.gradle"
touch "$HOME/.gradle/gradle.properties"
if ! grep -q "org.gradle.java.installations.paths" "$HOME/.gradle/gradle.properties"; then
    echo "org.gradle.java.installations.paths=$JDK_DIR" >> "$HOME/.gradle/gradle.properties"
fi

log "Installing sourcemeta jsonschema $JSONSCHEMA_VERSION"
if ! command -v jsonschema >/dev/null 2>&1; then
    tmp="$(mktemp -d)"
    if curl -fsSL -o "$tmp/jsonschema.zip" \
        "https://github.com/sourcemeta/jsonschema/releases/download/v${JSONSCHEMA_VERSION}/jsonschema-${JSONSCHEMA_VERSION}-linux-x86_64.zip"; then
        unzip -qo "$tmp/jsonschema.zip" -d "$tmp"
        bin="$(find "$tmp" -type f -name jsonschema | head -1)"
        [ -n "$bin" ] && sudo install -m 0755 "$bin" /usr/local/bin/jsonschema
    fi
    rm -rf "$tmp"
    if command -v jsonschema >/dev/null 2>&1; then
        jsonschema --version
    else
        echo "  NOTE: jsonschema not installed - it is only needed for the weekly publish pipeline."
    fi
fi

log "Installing Claude Code"
if ! command -v claude >/dev/null 2>&1; then
    curl -fsSL https://claude.ai/install.sh | bash
fi

log "Base setup complete. Remaining manual steps (they need secrets / host-specific values):"
cat <<EOF

  1) Authenticate gh (device-code flow works over SSH, no browser needed):
       gh auth login

  2) Add your GitHub Packages read token so the build can resolve kommand.
     Put these in ~/.gradle/gradle.properties (home dir - never in the repo):
       GH_USERNAME=${GITHUB_OWNER}
       GH_PACKAGES_READ_TOKEN=<a classic PAT with read:packages>

  3) Authenticate Claude Code (pick one; headless-friendly):
       export ANTHROPIC_API_KEY="sk-ant-..."          # from console.anthropic.com
     or run 'claude setup-token' on a machine with a browser, then on the server:
       export CLAUDE_CODE_OAUTH_TOKEN="..."

  4) Clone the dataset repo as a sibling (the crawl's output target):
       git clone https://github.com/${GITHUB_OWNER}/anime-offline-database.git

  5) Build the app (from the modb-app repo root):
       ./gradlew :app:shadowJar

  6) Create the working directories and a config file with YOUR real values
     (config mechanism is described in core/README.md#configuration-management):
       modb.app.downloadsDirectory            = <a directory for raw + converted files>
       modb.app.outputDirectory               = <the anime-offline-database clone>
       modb.app.downloadControlStateDirectory = <a directory for DCS files>
       modb.app.networkInterface              = <your interface, e.g. from 'ip -o link'>
       modb.app.ipv6Prefix                    = <your routed /64 in CIDR, e.g. from 'ip -6 addr'>

  7) Seed the baseline dataset from the latest release:
       cd anime-offline-database && make init-or-reset

  8) Run inside tmux so it survives disconnects, and watch it:
       tmux new -s crawl
       java -Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false -jar modb-app.jar
EOF
