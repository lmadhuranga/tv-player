#!/usr/bin/env bash
# Provision a per-TV, mkcert-signed local HTTPS certificate.
# Secrets stay outside Git and in the DEBUG app's private storage.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="dev.lmadhuranga.tvplayer"
OUT="$ROOT/.local-https"
MODE="${1:-install}"

command -v adb >/dev/null || { echo "adb is required. Install Android SDK platform-tools." >&2; exit 1; }
[[ "$MODE" == install || "$MODE" == disable || "$MODE" == status ]] || {
  echo "Usage: bash scripts/https-tv.sh [install|status|disable]" >&2
  exit 2
}

bash "$ROOT/scripts/tv.sh" discover
TV_SERIAL=""
TV_IP="${TV_IP:-}"
while IFS='=' read -r key value; do
  case "$key" in
    TV_SERIAL) TV_SERIAL="$value" ;;
    TV_IP) [[ -n "$TV_IP" ]] || TV_IP="$value" ;;
  esac
done < "$ROOT/.tv.env"

[[ -n "$TV_SERIAL" && -n "$TV_IP" ]] || {
  echo "Could not determine the connected TV IP. Set TV_IP and try again." >&2
  exit 1
}
[[ "$TV_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "The TV IP must be IPv4, not: $TV_IP" >&2
  exit 1
}

if ! adb -s "$TV_SERIAL" shell -T "run-as $APP_ID pwd" >/dev/null 2>&1; then
  echo "Install the DEBUG APK first: bash scripts/tv.sh deploy" >&2
  echo "Android run-as works with debug builds, not release-signed builds." >&2
  exit 1
fi

if [[ "$MODE" == status ]]; then
  if adb -s "$TV_SERIAL" shell -T "run-as $APP_ID test -f files/lan-https.enabled" >/dev/null 2>&1; then
    echo "HTTPS is configured on $TV_SERIAL. Open https://$TV_IP:8765/files"
  else
    echo "HTTPS is not configured. TV Player is still using HTTP."
  fi
  exit 0
fi

if [[ "$MODE" == disable ]]; then
  # Explicit opt-out only; certificate errors must never cause a silent downgrade.
  adb -s "$TV_SERIAL" shell -T "run-as $APP_ID sh -c 'rm -f files/lan-https.enabled files/lan-https.p12 files/lan-https.pass'"
  adb -s "$TV_SERIAL" shell am force-stop "$APP_ID"
  adb -s "$TV_SERIAL" shell am start -n "$APP_ID/.MainActivity"
  echo "HTTPS configuration explicitly removed. The app uses HTTP again."
  exit 0
fi

command -v mkcert >/dev/null || {
  echo "mkcert is required: brew install mkcert" >&2
  echo "After installation run: mkcert -install (to trust its local CA on this Mac)" >&2
  exit 1
}
command -v openssl >/dev/null || {
  echo "openssl is required to create the Android PKCS12 keystore." >&2
  exit 1
}

umask 077
mkdir -p "$OUT"
chmod 700 "$OUT"
echo "Creating a local certificate for TV IP $TV_IP..."
mkcert -cert-file "$OUT/server.pem" -key-file "$OUT/server-key.pem" "$TV_IP"
openssl rand -hex 32 > "$OUT/lan-https.pass"
openssl pkcs12 -export \
  -in "$OUT/server.pem" -inkey "$OUT/server-key.pem" \
  -out "$OUT/lan-https.p12" -name "tv-player-lan" \
  -passout "file:$OUT/lan-https.pass" \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1

# ADB -T streams unmodified bytes without corrupting a PKCS12 file.
# run-as writes directly to app-private storage (not /sdcard).
adb -s "$TV_SERIAL" shell -T "run-as $APP_ID sh -c 'mkdir -p files && cat > files/lan-https.p12'" < "$OUT/lan-https.p12"
adb -s "$TV_SERIAL" shell -T "run-as $APP_ID sh -c 'cat > files/lan-https.pass'" < "$OUT/lan-https.pass"

LOCAL_SHA="$(shasum -a 256 "$OUT/lan-https.p12" | awk '{print $1}')"
TV_SHA="$(adb -s "$TV_SERIAL" shell -T "run-as $APP_ID sha256sum files/lan-https.p12" | awk '{print $1}' | tr -d '\r')"
[[ "$LOCAL_SHA" == "$TV_SHA" ]] || {
  echo "Certificate transfer verification failed. HTTPS was not enabled." >&2
  exit 1
}

adb -s "$TV_SERIAL" shell -T "run-as $APP_ID sh -c 'chmod 600 files/lan-https.p12 files/lan-https.pass && printf enabled > files/lan-https.enabled'"
adb -s "$TV_SERIAL" shell am force-stop "$APP_ID"
adb -s "$TV_SERIAL" shell am start -n "$APP_ID/.MainActivity"

echo ""
echo "TV HTTPS provisioned:"
echo "  URL: https://$TV_IP:8765/files"
echo "  Local CA certificate: $(mkcert -CAROOT)/rootCA.pem"
echo "  MAC: Run mkcert -install once if not done."
echo "  OTHER DEVICES: Import and trust rootCA.pem (NEVER rootCA-key.pem)."
echo "  IP changes: re-run this provisioning script."
echo "  Keep TV Player on the Send to TV screen during transfers."
echo ""
echo "Verify from this Mac after the TV server starts:"
echo "  curl --cacert \"$(mkcert -CAROOT)/rootCA.pem\" \"https://$TV_IP:8765/files\" -o /dev/null -v"
