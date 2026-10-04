#!/usr/bin/env python3
"""Generate the SideStore/AltStore source (source.json) from GitHub Releases.

For every app in .github/sideload-source.json, scans that repo's releases for an .ipa
asset with a matching `<ipa>.json` sidecar (written by build-ios-unsigned.yml) and lists
the newest `maxVersions` of them. Download URLs point straight at the public release
assets, so nothing but the manifest and icons needs hosting.

Usage: gen_sideload_source.py <out_dir>   (GH_TOKEN optional, raises the API rate limit)
"""
import json, os, shutil, sys, urllib.request

CONFIG = os.path.join(os.path.dirname(__file__), "..", "sideload-source.json")


def get(url: str):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    if url.startswith("https://api.github.com") and os.environ.get("GH_TOKEN"):
        req.add_header("Authorization", f"Bearer {os.environ['GH_TOKEN']}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def versions_for(app: dict) -> list:
    releases = get(f"https://api.github.com/repos/{app['repo']}/releases?per_page=50")
    out = []
    for rel in releases:  # newest first
        if rel["draft"]:
            continue
        assets = {a["name"]: a for a in rel["assets"]}
        for name, ipa in assets.items():
            if not name.endswith(".ipa") or name + ".json" not in assets:
                continue
            meta = get(assets[name + ".json"]["browser_download_url"])
            out.append({
                "version": meta["version"],
                "buildVersion": meta["buildVersion"],
                "date": rel["published_at"],
                "localizedDescription": (rel.get("body") or rel["name"] or "")[:4000],
                "downloadURL": ipa["browser_download_url"],
                "size": ipa["size"],
                "minOSVersion": meta.get("minOSVersion") or None,
                "_bundle": meta["bundleIdentifier"],
                "_privacy": meta.get("privacy", {}),
            })
            break
        if len(out) >= app.get("maxVersions", 5):
            break
    return out


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    with open(CONFIG) as f:
        cfg = json.load(f)
    repo_root = os.path.join(os.path.dirname(__file__), "..", "..")
    base = cfg["sourceURL"].rsplit("/", 1)[0]

    apps = []
    for app in cfg["apps"]:
        versions = versions_for(app)
        if not versions:
            print(f"!! {app['name']}: no release with an .ipa + .json sidecar yet, skipped")
            continue
        latest = versions[0]
        icon = app["icon"]
        if not icon.startswith("http"):  # local file in this repo -> publish next to source.json
            icon_name = f"{latest['_bundle']}.png"
            shutil.copy(os.path.join(repo_root, icon), os.path.join(out_dir, icon_name))
            icon = f"{base}/{icon_name}"
        entry = {
            "name": app["name"],
            "bundleIdentifier": latest["_bundle"],
            "developerName": app["developerName"],
            "subtitle": app.get("subtitle", ""),
            "localizedDescription": app.get("localizedDescription", ""),
            "iconURL": icon,
            "tintColor": app.get("tintColor", ""),
            "category": app.get("category", "other"),
            "screenshotURLs": [],
            "appPermissions": {"entitlements": [], "privacy": latest["_privacy"]},
            "versions": [{k: v for k, v in ver.items() if not k.startswith("_") and v is not None}
                         for ver in versions],
            # AltStore 1.x-style top-level fields, still read by some clients
            "version": latest["version"],
            "versionDate": latest["date"],
            "versionDescription": latest["localizedDescription"],
            "downloadURL": latest["downloadURL"],
            "size": latest["size"],
        }
        apps.append(entry)
        print(f">> {app['name']} {latest['version']} ({latest['buildVersion']}), {len(versions)} version(s)")

    source = {
        "name": cfg["name"],
        "identifier": cfg["identifier"],
        "sourceURL": cfg["sourceURL"],
        "apps": apps,
        "news": [],
    }
    with open(os.path.join(out_dir, "source.json"), "w") as f:
        json.dump(source, f, indent=2)
    print(f">> wrote {out_dir}/source.json — add in SideStore: {cfg['sourceURL']}")


if __name__ == "__main__":
    main()
