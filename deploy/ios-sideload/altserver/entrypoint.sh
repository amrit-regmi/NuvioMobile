#!/usr/bin/env bash
# AltServer-Linux control script. Modes:
#   install-altstore  ONE-TIME, interactive (attach a TTY): logs into your Apple ID
#                     (2FA prompt here) and installs the AltStore app onto the phone.
#                     After this, you browse/install OTHER apps from AltStore on-device.
#   install-ipa       Directly push a local .ipa from /ipa (e.g. CineX) via AltServer,
#                     without going through the AltStore UI. Interactive first time.
#   daemon            Long-running: keeps netmuxd + AltServer alive so AltStore on the
#                     phone can install & refresh apps wirelessly, and periodically
#                     re-refreshes AltStore itself so it never expires (7-day free cert).
set -euo pipefail

: "${APPLE_ID:?set APPLE_ID in secrets/altserver.env}"
: "${APPLE_ID_PASSWORD:?set APPLE_ID_PASSWORD in secrets/altserver.env}"
: "${ALTSERVER_ANISETTE_SERVER:=http://127.0.0.1:6969}"
: "${REFRESH_INTERVAL_DAYS:=6}"
export ALTSERVER_ANISETTE_SERVER

MODE="${1:-daemon}"

start_netmuxd() {
  # netmuxd bridges the host usbmuxd socket + advertises the device over mDNS so AltStore
  # (on the phone) can reach AltServer wirelessly. Keep it alive for the whole session.
  pgrep -x netmuxd >/dev/null 2>&1 || { netmuxd --disable-unix & sleep 2; }
}

detect_udid() { idevice_id -l 2>/dev/null | head -1; }

require_device() {
  local udid; udid=$(detect_udid)
  [ -n "$udid" ] || { echo "!! no device detected — is the iPhone plugged in and trusted?"; exit 1; }
  echo "$udid"
}

fetch_altstore() {
  # Always resolve the current AltStore (Classic) .ipa from the official source manifest.
  local url
  url=$(curl -fsSL https://cdn.altstore.io/file/altstore/apps.json \
        | jq -r '.apps[] | select(.bundleIdentifier=="com.rileytestut.AltStore")
                 | (.versions[0].downloadURL // .downloadURL)' | head -1)
  [ -n "$url" ] || { echo "!! could not resolve AltStore .ipa url"; exit 1; }
  echo ">> AltStore: $url" >&2
  curl -fSL -o /tmp/AltStore.ipa "$url"
  echo /tmp/AltStore.ipa
}

install_app() {   # $1 = path to .ipa
  local udid ipa="$1"
  udid=$(require_device)
  echo "==> Installing $ipa to $udid as $APPLE_ID"
  AltServer -u "$udid" -a "$APPLE_ID" -p "$APPLE_ID_PASSWORD" "$ipa"
}

case "$MODE" in
  install-altstore)
    start_netmuxd
    install_app "$(fetch_altstore)"    # interactive: enter the 2FA code at the prompt
    echo "==> AltStore installed. On the phone: open AltStore, sign in with the same Apple ID,"
    echo "    then install CineX and any other apps from there. Keep this box running:"
    echo "    docker compose up -d altserver"
    ;;
  install-ipa)
    start_netmuxd
    ipa=$(ls /ipa/*.ipa 2>/dev/null | head -1) || true
    [ -n "$ipa" ] || { echo "!! no .ipa in /ipa"; exit 1; }
    install_app "$ipa"
    ;;
  daemon)
    start_netmuxd
    # Foreground supervisor: keep AltServer running (so AltStore can install/refresh apps
    # wirelessly), and every REFRESH_INTERVAL_DAYS nudge AltStore's own cert so it can't lapse.
    # If a refresh is OVERDUE but the phone is unreachable (off-LAN / rebooted-but-locked), we
    # poll frequently so it fires the moment the phone comes back — never waits a whole cycle.
    last_refresh=0
    while true; do
      if ! pgrep -x AltServer >/dev/null 2>&1; then
        echo "==> $(date -u +%FT%TZ) (re)starting AltServer listener"
        AltServer &   # server mode: services AltStore install/refresh requests over netmuxd
      fi
      now=$(date +%s)
      sleep_for=3600
      if [ $(( now - last_refresh )) -ge $(( REFRESH_INTERVAL_DAYS * 24 * 3600 )) ]; then
        if udid=$(detect_udid) && [ -n "$udid" ]; then
          echo "==> $(date -u +%FT%TZ) refresh due — device $udid reachable, re-signing AltStore"
          if install_app "$(fetch_altstore)"; then last_refresh=$now
          else echo "!! AltStore refresh failed; will retry shortly"; sleep_for=300; fi
        else
          echo "==> $(date -u +%FT%TZ) refresh due but no device (off-LAN/locked) — polling for reconnect"
          sleep_for=300      # catch the phone reconnecting within ~5 min, not the next 6-day tick
        fi
      fi
      sleep "$sleep_for"
    done
    ;;
  *)
    echo "usage: entrypoint.sh {install-altstore|install-ipa|daemon}"; exit 2 ;;
esac
