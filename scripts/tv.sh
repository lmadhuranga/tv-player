#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.tv.env"
MODE="${1:-deploy}"

command -v adb >/dev/null || { echo 'Android SDK adb was not found in PATH.' >&2; exit 1; }

TV_SERIAL="${TV_SERIAL:-}"
TV_IP="${TV_IP:-}"
if [[ -f "$CACHE" ]]; then
  while IFS='=' read -r key value; do
    case "$key" in
      TV_SERIAL) [[ -n "$TV_SERIAL" ]] || TV_SERIAL="$value" ;;
      TV_IP) [[ -n "$TV_IP" ]] || TV_IP="$value" ;;
    esac
  done < "$CACHE"
fi

is_tv() {
  local serial="$1" status props model leanback
  status="$(adb -s "$serial" get-state 2>/dev/null || true)"
  [[ "$status" == "device" ]] || return 1
  props="$(adb -s "$serial" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r' || true)"
  model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  leanback="$(adb -s "$serial" shell pm has-feature android.software.leanback 2>/dev/null | tr -d '\r' || true)"
  [[ "$leanback" == "true" || "$props" == *tv* || "$model" == *BRAVIA* || "$model" == *Bravia* ]]
}

select_tv() {
  local serial="$1"
  if is_tv "$serial"; then
    TV_SERIAL="$serial"
    if [[ "$serial" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+: ]]; then
      TV_IP="${serial%%:*}"
    fi
    return 0
  fi
  return 1
}

if [[ -n "$TV_SERIAL" && "$TV_SERIAL" == *:* ]]; then
  adb connect "$TV_SERIAL" >/dev/null 2>&1 || true
elif [[ -n "$TV_IP" ]]; then
  adb connect "$TV_IP:5555" >/dev/null 2>&1 || true
fi

if [[ -n "$TV_SERIAL" ]] && select_tv "$TV_SERIAL"; then
  :
else
  TV_SERIAL=""
  while read -r serial state _; do
    [[ "$state" == "device" ]] || continue
    if select_tv "$serial"; then break; fi
  done < <(adb devices | tail -n +2)
fi

if [[ -z "$TV_SERIAL" ]]; then
  while read -r candidate; do
    [[ "$candidate" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+$ ]] || continue
    adb connect "$candidate" >/dev/null 2>&1 || true
    if select_tv "$candidate"; then break; fi
  done < <(adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $NF}' || true)
fi

if [[ -z "$TV_SERIAL" ]]; then
  LAN_IP=""
  if [[ "$(uname)" == "Darwin" ]]; then
    iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2;exit}')"
    [[ -z "$iface" ]] || LAN_IP="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
  elif command -v ip >/dev/null; then
    iface="$(ip route show default 2>/dev/null | awk '/default/{print $5;exit}')"
    [[ -z "$iface" ]] || LAN_IP="$(ip -4 -o addr show dev "$iface" | awk '{print $4}' | cut -d/ -f1 | head -1)"
  fi
  if [[ "$LAN_IP" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.[0-9]+$ ]] && command -v nc >/dev/null; then
    subnet="${BASH_REMATCH[1]}"
    echo "Searching $subnet.0/24 for an Android TV on ADB port 5555..." >&2
    while read -r candidate; do
      [[ -n "$candidate" ]] || continue
      adb connect "$candidate:5555" >/dev/null 2>&1 || true
      if select_tv "$candidate:5555"; then break; fi
    done < <(seq 1 254 | awk -v s="$subnet" '{print s "." $1}' | \
      xargs -P 32 -n 1 sh -c 'nc -z -w 1 "$1" 5555 >/dev/null 2>&1 && printf "%s\n" "$1"' _)
  fi
fi

if [[ -z "$TV_SERIAL" ]]; then
  echo 'No Android TV found. Enable ADB debugging on the TV and use the same Wi-Fi.' >&2
  echo 'For Wireless debugging pairing, first run: adb pair TV_IP:PAIR_PORT' >&2
  echo 'Then: adb connect TV_IP:DEBUG_PORT; rerun this script.' >&2
  exit 1
fi

if [[ "$TV_SERIAL" == *:* && "$TV_SERIAL" == *.* ]]; then
  TV_IP="${TV_SERIAL%%:*}"
fi
umask 077
printf 'TV_SERIAL=%s\nTV_IP=%s\n' "$TV_SERIAL" "$TV_IP" > "$CACHE"
echo "TV detected: $TV_SERIAL" >&2

case "$MODE" in
  discover|connect) ;;
  env) printf 'export TV_SERIAL=%q\nexport TV_IP=%q\n' "$TV_SERIAL" "$TV_IP" ;;
  deploy)
    "$ROOT/gradlew" testDebugUnitTest assembleDebug --console=plain --info
    apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$apk" ]] || { echo "Missing APK: $apk" >&2; exit 1; }
    adb -s "$TV_SERIAL" install -r "$apk"
    adb -s "$TV_SERIAL" shell am start -n dev.lmadhuranga.tvplayer/.MainActivity
    ;;
  *) echo 'Usage: bash scripts/tv.sh [discover|env|deploy]' >&2; exit 2 ;;
esac
