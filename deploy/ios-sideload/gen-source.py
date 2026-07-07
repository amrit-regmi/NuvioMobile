#!/usr/bin/env python3
"""Generate a self-hosted AltStore source (apps.json) for the CineX .ipa.

Reads the newest .ipa in ./ipa/, pulls version + bundle id + display name from its
Info.plist and the largest AppIcon from the bundle, then writes ./web/:
    CineX.ipa   the app payload the phone downloads
    icon.png    the app icon (rendered by AltStore on-device)
    apps.json   the AltStore source manifest pointing at the two files above

The download/icon URLs must be reachable BY THE PHONE, so they use the box's LAN
address. Override with SOURCE_BASE_URL (default http://192.168.10.128:8843).

Re-run this whenever a new .ipa lands (bootstrap.sh calls it); AltStore then shows the
new version and updates over the air.
"""
import glob, json, os, plistlib, shutil, sys, zipfile
from datetime import date, timezone, datetime

BASE = os.environ.get("SOURCE_BASE_URL", "http://192.168.10.128:8843").rstrip("/")
HERE = os.path.dirname(os.path.abspath(__file__))
IPA_DIR = os.path.join(HERE, "ipa")
WEB = os.path.join(HERE, "web")


def newest_ipa() -> str:
    ipas = sorted(glob.glob(os.path.join(IPA_DIR, "*.ipa")), key=os.path.getmtime)
    if not ipas:
        sys.exit(f"!! no .ipa in {IPA_DIR} — run bootstrap.sh (or drop one in) first")
    return ipas[-1]


def read_meta(ipa: str):
    with zipfile.ZipFile(ipa) as z:
        info = next(n for n in z.namelist()
                    if n.count("/") == 2 and n.endswith(".app/Info.plist"))
        pl = plistlib.loads(z.read(info))
        # largest AppIcon png in the bundle (Apple-optimized PNG renders fine in AltStore/iOS)
        icons = [n for n in z.namelist()
                 if "/AppIcon" in n and n.lower().endswith(".png")]
        icon_bytes = None
        if icons:
            biggest = max(icons, key=lambda n: z.getinfo(n).file_size)
            icon_bytes = z.read(biggest)
    return pl, icon_bytes


def main():
    ipa = newest_ipa()
    pl, icon_bytes = read_meta(ipa)
    version = pl.get("CFBundleShortVersionString", "0.0.0")
    bundle_id = pl.get("CFBundleIdentifier", "com.netflix.ninjax")
    # Store-listing name is our brand. NB: the on-phone app label comes from the .ipa's
    # CFBundleDisplayName (still "Nuvio" until the iOS display name is rebranded in-app).
    name = "CineX"
    min_os = pl.get("MinimumOSVersion", "16.1")
    size = os.path.getsize(ipa)
    today = date.today().isoformat()

    os.makedirs(WEB, exist_ok=True)
    shutil.copy2(ipa, os.path.join(WEB, "CineX.ipa"))
    if icon_bytes:
        with open(os.path.join(WEB, "icon.png"), "wb") as f:
            f.write(icon_bytes)
    elif not os.path.exists(os.path.join(WEB, "icon.png")):
        print("!! no AppIcon found in .ipa and no web/icon.png present — drop a PNG there")

    entry = {
        "version": version, "date": today,
        "localizedDescription": f"CineX {version}",
        "downloadURL": f"{BASE}/CineX.ipa",
        "size": size, "minOSVersion": min_os,
    }
    app = {
        "name": name,
        "bundleIdentifier": bundle_id,
        "developerName": "CineX",
        "subtitle": "Private streaming client",
        "localizedDescription": "CineX — private streaming client. Self-hosted build.",
        "iconURL": f"{BASE}/icon.png",
        "tintColor": "1e1e1e",
        "category": "entertainment",
        "screenshotURLs": [],
        # legacy top-level fields (AltStore 1.x) + versions[] (AltStore 2.x)
        "version": version, "versionDate": today,
        "versionDescription": f"CineX {version}",
        "downloadURL": f"{BASE}/CineX.ipa", "size": size,
        "versions": [entry],
    }
    manifest = {
        "name": "CineX",
        "identifier": "com.netflix.ninjax.altstore-source",
        "sourceURL": f"{BASE}/apps.json",
        "apps": [app],
        "news": [],
    }
    with open(os.path.join(WEB, "apps.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    print(f">> source generated at {WEB}")
    print(f"   app     : {name} {version} ({bundle_id}), {size/1e6:.0f} MB")
    print(f"   add this in AltStore (Browse → Sources → +):  {BASE}/apps.json")


if __name__ == "__main__":
    main()
