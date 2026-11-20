#!/usr/bin/env bash
# يُنشئ مفتاح توقيع دائمًا على جهازك (يحتاج JDK / keytool) ويطبع بصمة SHA-256 للشهادة.
#   ./tools/make-keystore.sh [alias] [اسم الملف]
set -euo pipefail
ALIAS="${1:-ghmanager}"
OUT="${2:-release.jks}"
[ -e "$OUT" ] && { echo "الملف $OUT موجود مسبقًا — لن أستبدله." >&2; exit 1; }
read -r -s -p "كلمة مرور المفتاح (12 حرفًا فأكثر): " PASS; echo
[ "${#PASS}" -ge 12 ] || { echo "كلمة المرور قصيرة." >&2; exit 1; }
keytool -genkeypair -keystore "$OUT" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=GitHub Manager"
echo
echo "SHA-256 للشهادة:"
keytool -list -v -keystore "$OUT" -alias "$ALIAS" -storepass "$PASS" | grep -m1 'SHA256:' | sed 's/.*SHA256:[[:space:]]*//'
echo
echo "احتفظ بنسخة احتياطية من $OUT وكلمة المرور. لا ترفعهما إلى المستودع."
