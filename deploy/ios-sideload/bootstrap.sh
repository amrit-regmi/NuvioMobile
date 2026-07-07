#!/usr/bin/env bash
# One-time host prep for the iOS sideload box (Debian x86_64, 192.168.10.128).
# Idempotent and reviewable. Does NOT touch your Apple credentials and does NOT
# start anything that signs — it only prepares the host and fetches the .ipa.
#
# What it does:
#   1. ensures Docker + compose plugin are present
#   2. installs the SINGLE host package we allow: usbmuxd (+ starts it)
#   3. downloads the latest unsigned CineX .ipa from CI into ./ipa/
#   4. scaffolds ./secrets/altserver.env for YOU to fill in (chmod 600)
# It deliberately stops short of building/starting the stack so you can review
# everything first. See README.md for the (interactive) bring-up steps.
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1. Docker =="
if ! command -v docker >/dev/null 2>&1; then
  echo ">> installing Docker Engine (official convenience script)"
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null 2>&1 || { echo "!! docker compose plugin missing"; exit 1; }

echo "== 2. usbmuxd (the only host package) =="
if ! dpkg -s usbmuxd >/dev/null 2>&1; then
  sudo apt-get update && sudo apt-get install -y usbmuxd
fi
sudo systemctl enable --now usbmuxd 2>/dev/null || true
systemctl is-active usbmuxd >/dev/null 2>&1 && echo ">> usbmuxd active" || echo "!! usbmuxd not active — check 'systemctl status usbmuxd'"

echo "== 3. fetch latest unsigned CineX .ipa from CI =="
if command -v gh >/dev/null 2>&1; then
  RID=$(gh run list --repo amrit-regmi/NuvioMobile --workflow=build-ios-unsigned.yml \
        --branch ci/ios-build -L 1 --json databaseId,conclusion \
        --jq '.[] | select(.conclusion=="success") | .databaseId' | head -1)
  if [ -n "${RID:-}" ]; then
    tmp=$(mktemp -d)
    gh run download "$RID" --repo amrit-regmi/NuvioMobile -D "$tmp"
    ipa=$(find "$tmp" -type f -name '*.ipa' | head -1)
    if [ -n "$ipa" ]; then cp "$ipa" ./ipa/; echo ">> placed $(basename "$ipa") in ./ipa/"; fi
    rm -rf "$tmp"
  else
    echo "!! no successful CI run found — drop the .ipa into ./ipa/ manually"
  fi
else
  echo "!! gh CLI not installed — drop the .ipa into ./ipa/ manually (or 'apt install gh' + 'gh auth login')"
fi

echo "== 4. generate the self-hosted CineX AltStore source (web/) =="
if command -v python3 >/dev/null 2>&1 && ls ipa/*.ipa >/dev/null 2>&1; then
  # SOURCE_BASE_URL must be the address the PHONE uses to reach your Caddy — override it
  # to match the Caddyfile.snippet block you pick, then this regenerates apps.json to suit.
  python3 gen-source.py || echo "!! gen-source.py failed — you can re-run it after fixing"
else
  echo ">> skipped (need python3 + an .ipa in ./ipa/); run 'python3 gen-source.py' later"
fi

echo "== 5. secrets scaffold =="
if [ ! -f secrets/altserver.env ]; then
  cat > secrets/altserver.env <<'EOF'
# YOUR free Apple ID. Fill both in, then keep this file at chmod 600. Never commit it.
APPLE_ID=you@example.com
APPLE_ID_PASSWORD=
EOF
  chmod 600 secrets/altserver.env
  echo ">> created secrets/altserver.env (chmod 600) — edit it with YOUR Apple ID + password"
else
  echo ">> secrets/altserver.env already exists (left untouched)"
fi

echo
echo "Host prep done. Next (see README.md):"
echo "  1) edit secrets/altserver.env with your Apple ID + password"
echo "  2) point your existing Caddy at ./web (see Caddyfile.snippet), 'caddy reload'"
echo "  3) plug in the iPhone and tap Trust"
echo "  4) docker compose build && docker compose up -d anisette"
echo "  5) docker compose run --rm altserver install-altstore   # interactive: enter 2FA code"
echo "  6) docker compose up -d altserver                        # keep AltServer alive"
echo "  7) on the phone: open AltStore, sign in, then Browse -> Sources -> + and add"
echo "     your source URL (SOURCE_BASE_URL + /apps.json) to install/update CineX + others"
