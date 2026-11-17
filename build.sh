#!/usr/bin/env bash
# Baut die OWUI-LAN-Chat-APK ohne Gradle:
# aapt2 compile -> aapt2 link (R.java) -> javac(android.jar) -> d8 -> dex in APK -> zipalign -> apksigner
#
# Signatur: Keystore build/owui-release.keystore (oder OWUI_KEYSTORE), Passwort aus
# OWUI_KEYPASS bzw. <keystore>.pass. Keystore und Passwort liegen nicht im Repository.
# OWUI_NO_CA=1  -> assets/ca.crt wird NICHT in die APK gepackt (für öffentliche Builds)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/owui-android/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34"
MIN_API=26
TARGET_API=34

cd "$(dirname "$0")"
OUT=build/apk
KEYSTORE="${OWUI_KEYSTORE:-build/owui-release.keystore}"
KEYPASS_FILE="${OWUI_KEYPASS_FILE:-$KEYSTORE.pass}"
KEY_DNAME="${OWUI_KEY_DNAME:-CN=OWUI LAN Chat, O=owui-chat, C=DE}"

if [ ! -x "$BT/aapt2" ] || [ ! -f "$PLAT/android.jar" ]; then
  echo "SDK fehlt: $BT/aapt2 bzw. $PLAT/android.jar"; exit 1
fi

echo "== [1/6] Resourcen kompilieren =="
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

LINK_ASSETS=assets
if [ "${OWUI_NO_CA:-0}" = "1" ] && [ -f assets/ca.crt ]; then
  LINK_ASSETS="$OUT/assets_pub"
  mkdir -p "$LINK_ASSETS"
  find assets -maxdepth 1 -type f ! -name ca.crt -exec cp {} "$LINK_ASSETS" \;
  echo "    OWUI_NO_CA=1: assets/ca.crt bleibt außerhalb der APK"
elif [ ! -f assets/ca.crt ]; then
  echo "    Hinweis: kein assets/ca.crt -> HTTPS nur mit öffentlich vertrauenswürdigem Zertifikat"
fi

echo "== [2/6] APK linken (Resourcen, Assets, R.java) =="
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  --manifest AndroidManifest.xml \
  --min-sdk-version "$MIN_API" \
  --target-sdk-version "$TARGET_API" \
  -A "$LINK_ASSETS" \
  --java "$OUT/gen" \
  -I "$PLAT/android.jar" \
  "$OUT"/res.zip
echo "    R.java: $(find "$OUT/gen" -name 'R.java' | head -1)"

echo "== [3/6] Java kompilieren (gegen android.jar) =="
javac -nowarn -Xmaxerrs 40 -encoding UTF-8 -Xlint:-options --release 11 \
      -classpath "$PLAT/android.jar" \
      -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
echo "    $(find "$OUT/classes" -name '*.class' | wc -l) Klassen"

echo "== [4/6] dexen (d8) =="
find "$OUT/classes" -name '*.class' -print0 \
  | xargs -0 "$BT/d8" --lib "$PLAT/android.jar" --min-api "$MIN_API" --release --output "$OUT/dex"
python3 - "$OUT" <<'PY'
import sys, zipfile, os, shutil
out = sys.argv[1]
src, dst = os.path.join(out, "base.apk"), os.path.join(out, "base_tmp.apk")
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, "w")
for item in zin.infolist():
    data = zin.read(item.filename)
    # resources.arsc bleibt unkomprimiert, zipalign richtet die Ausrichtung danach aus
    mode = zipfile.ZIP_STORED if (item.filename == "resources.arsc" or item.compress_type == zipfile.ZIP_STORED) else zipfile.ZIP_DEFLATED
    zout.writestr(item, data, compress_type=mode)
zout.writestr("classes.dex", open(os.path.join(out, "dex", "classes.dex"), "rb").read(), zipfile.ZIP_STORED)
zout.close(); zin.close()
shutil.move(dst, src)
PY

echo "== [5/6] zipalign =="
KEYPASS="${OWUI_KEYPASS:-}"
if [ -z "$KEYPASS" ] && [ -f "$KEYPASS_FILE" ]; then KEYPASS="$(cat "$KEYPASS_FILE")"; fi
if [ ! -f "$KEYSTORE" ]; then
  [ -z "$KEYPASS" ] && KEYPASS="$(openssl rand -hex 12)"
  printf '%s\n' "$KEYPASS" > "$KEYPASS_FILE"
  chmod 600 "$KEYPASS_FILE"
  echo "    neues Signier-Keystore -> $KEYSTORE, Passwort in $KEYPASS_FILE (beides sichern!)"
  keytool -genkeypair -keystore "$KEYSTORE" -alias owui-lan \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -dname "$KEY_DNAME" >/dev/null 2>&1
elif [ -z "$KEYPASS" ]; then
  echo "    Passwort für $KEYSTORE fehlt: OWUI_KEYPASS setzen oder $KEYPASS_FILE anlegen"; exit 1
fi
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== [6/6] signen + pruefen =="
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias owui-lan \
  --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --min-sdk-version "$MIN_API" --max-sdk-version "$TARGET_API" \
  --out "$OUT/owui-chat.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/owui-chat.apk" | sed -n '1,4p'
echo "    Inhalt:"; unzip -l "$OUT/owui-chat.apk" | awk 'NR<=4 || /classes.dex|assets|resources.arsc/ {print "      " $4}' | head -12
echo "FERTIG: $(pwd)/$OUT/owui-chat.apk  ($(stat -c %s "$OUT/owui-chat.apk") Bytes)"
