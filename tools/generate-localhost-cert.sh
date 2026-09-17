#!/bin/sh
# Regenerates the self-signed certificate the proxy serves HTTPS with.
#
# The key is deliberately committed. It only ever authenticates 127.0.0.1, and any app
# on the device can already reach the proxy, so publishing it grants nothing that local
# access does not already give. Treating it as a secret would mean generating on device,
# which needs a certificate-building library for no security gain.
#
# SAN carries the IP, not just a CN: modern TLS stacks ignore CN entirely, so a
# certificate without subjectAltName=IP:127.0.0.1 fails validation even when trusted.
#
# Run from the repository root:  sh tools/generate-localhost-cert.sh
set -eu

OUT_DIR="app/src/main/assets"
PASSWORD="wmsproxy"
DAYS=7300

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
  -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
  -subj "/CN=localhost/O=WMSproxy" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth"

# PKCS12, not JKS: Android does not support the JKS keystore type.
openssl pkcs12 -export \
  -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
  -name wmsproxy -out "$OUT_DIR/localhost.p12" \
  -passout "pass:$PASSWORD"

# The bare certificate, for installing on a device that is willing to trust it.
cp "$TMP/cert.pem" "$OUT_DIR/localhost.crt"

echo "Wrote $OUT_DIR/localhost.p12 and $OUT_DIR/localhost.crt"
openssl x509 -in "$TMP/cert.pem" -noout -subject -dates -ext subjectAltName
