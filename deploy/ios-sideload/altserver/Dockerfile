# AltServer-Linux + netmuxd, x86_64. Binaries are pulled from their upstream GitHub
# releases at build time (resolved to the latest asset, so no stale pinned tag rots).
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl jq \
        libssl3 libavahi-client3 libavahi-common3 \
        libimobiledevice-utils usbmuxd \
    && rm -rf /var/lib/apt/lists/*

# AltServer-Linux (NyaMisty) — the re-signer/installer. Pick the x86_64 asset.
RUN url=$(curl -fsSL https://api.github.com/repos/NyaMisty/AltServer-Linux/releases/latest \
          | jq -r '.assets[].browser_download_url' | grep -iE 'AltServer-x86_64$' | head -1) \
    && echo "AltServer: $url" \
    && curl -fSL -o /usr/local/bin/AltServer "$url" \
    && chmod +x /usr/local/bin/AltServer

# netmuxd (jkcoxson) — registers the USB device (seen via the bind-mounted usbmuxd
# socket) so AltServer can reach it. Pick the x86_64 linux asset.
RUN url=$(curl -fsSL https://api.github.com/repos/jkcoxson/netmuxd/releases/latest \
          | jq -r '.assets[].browser_download_url' | grep -iE 'x86_64.*linux.*netmuxd$|netmuxd.*x86_64' | head -1) \
    && echo "netmuxd: $url" \
    && curl -fSL -o /usr/local/bin/netmuxd "$url" \
    && chmod +x /usr/local/bin/netmuxd

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
CMD ["daemon"]
