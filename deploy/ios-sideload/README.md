# iOS sideload via AltStore — Debian home server (option A)

Runs **AltStore** on a free Apple ID so you can install & auto-refresh CineX **and any other
app** from the phone. A headless Linux AltServer (AltServer-Linux + netmuxd) does the signing
in the background; AltStore on the phone is the store UI. Apps re-sign automatically before the
7-day free cert lapses. **Host footprint = Docker + one package (`usbmuxd`).**

```
docker-compose.yml        anisette (:6969) + altserver (AltServer-Linux + netmuxd)
altserver/Dockerfile      pulls latest AltServer-Linux + netmuxd (x86_64) at build
altserver/entrypoint.sh   install-altstore | install-ipa | daemon
gen-source.py             builds the self-hosted CineX AltStore source into web/
Caddyfile.snippet         how to serve web/ from YOUR existing Caddy
bootstrap.sh              host prep: docker + usbmuxd + fetch .ipa + gen source + secrets
secrets/altserver.env     YOUR Apple ID + password (chmod 600, git-ignored) — you create
ipa/                      staged .ipa(s) e.g. CineX (git-ignored)
web/                      generated source: apps.json + icon.png + CineX.ipa (git-ignored)
```

## The model
- **AltServer-Linux (this box)** — signs & installs; must stay running for AltStore to work.
- **netmuxd** — advertises the tethered iPhone over mDNS so AltStore reaches AltServer.
- **AltStore (on the phone)** — the store. You install/manage CineX + other apps from here.
- Free Apple ID limits: **3 apps** installed at once, **7-day** certs (we refresh every 6).

## The two steps only YOU can do
1. **Apple ID password** — typed by you into `secrets/altserver.env` on the box. Never leaves it.
2. **USB pairing + 2FA** — plug the iPhone in, tap **Trust**, and enter the 2FA code once at the
   interactive `install-altstore` step. Both are inherently on-device/on-box.

## Bring-up (run on 192.168.10.128)
```bash
cd deploy/ios-sideload

# the phone reaches Caddy over your home WiFi, so a plain LAN IP:port is all you need.
# (default is exactly this; only override for off-LAN/HTTPS — see Caddyfile.snippet.)
export SOURCE_BASE_URL=http://192.168.10.128:8843
./bootstrap.sh                        # docker + usbmuxd + pull .ipa + gen web/ + scaffold secrets
nano secrets/altserver.env            # <-- your Apple ID + password  (step 1)

# point your EXISTING Caddy at ./web (see Caddyfile.snippet), then: caddy reload   (step 2)

# plug in the iPhone, tap "Trust"      (step 3a)
docker compose build
docker compose up -d anisette
docker compose run --rm altserver install-altstore   # interactive: enter 2FA (step 3b)

docker compose up -d altserver        # keep AltServer alive for install/refresh
docker compose logs -f altserver
```
Then **on the phone**: open AltStore → sign in with the same Apple ID → **Browse → Sources → +**
and add `$SOURCE_BASE_URL/apps.json` → install **CineX** from it. Add other apps the same way
(other sources, or import an `.ipa`). First launch of any app: Settings → General → VPN & Device
Management → Trust your Apple ID.

## CineX = self-hosted AltStore source
`gen-source.py` reads the newest `.ipa` in `./ipa/`, pulls its version + icon, and writes
`web/{apps.json, icon.png, CineX.ipa}`. Your existing **Caddy** serves that folder (see
`Caddyfile.snippet` — dedicated port, path under an existing site, or a subdomain). AltStore then
installs **and auto-updates** CineX over the air, uniform with every other app you add.

Set **`SOURCE_BASE_URL`** to whatever address the *phone* uses to reach Caddy (must match the
Caddyfile block you chose) before running `bootstrap.sh` / `gen-source.py`, so the URLs baked into
`apps.json` are reachable.

> Prefer not to use AltStore for CineX? `docker compose run --rm altserver install-ipa` pushes the
> staged `./ipa/` straight through AltServer — but then AltStore won't manage CineX's updates.

## Day-to-day
- **Add other apps:** use AltStore on the phone (add other sources, or import an `.ipa`).
- **Ship a new CineX build:** `./bootstrap.sh` (pulls newest CI `.ipa` + regenerates `web/`) — no
  Caddy change needed; AltStore offers the update OTA on its next refresh.
- **USB is one-time.** The cable is only needed for the initial pair/trust + `install-altstore`
  (2FA). After that netmuxd refreshes over **WiFi** (same LAN) using the saved pairing record — no
  cable, no re-entering 2FA.
- **After a phone reboot: just unlock it.** The pairing record survives reboots; iOS only blocks
  trusted access until the first unlock (Before-First-Unlock). Once unlocked, wireless refresh
  resumes on its own. You'd only re-plug in the rare case the pairing was actually wiped.
- **A missed refresh self-heals.** Two layers cover it: AltStore on the phone refreshes whenever it
  can reach AltServer over WiFi, and the daemon, once a refresh is due, polls every ~5 min and fires
  the moment the phone is reachable again — it never waits out a full 6-day cycle.
- **Force a refresh now:** `docker compose restart altserver` (phone on the LAN, unlocked).
- **Tear down:** `docker compose down -v` + remove the Caddy block + `sudo apt remove usbmuxd`.

## Notes / verify on first run
- Bundle id is `com.netflix.ninjax` (same as the Android APK).
- The Downloads widget / Live Activity is stripped from the CineX `.ipa` (free accounts can't
  provision App Groups / ActivityKit) — expected.
- AltServer-Linux's persistent "server" behaviour and the exact refresh cadence can vary by
  release; the persisted `altserver-data` volume caches the Apple session so the daemon refreshes
  without re-prompting 2FA. If a refresh ever re-asks for 2FA, just re-run `install-altstore` once.
