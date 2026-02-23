#!/bin/bash

# Script to generate self-signed certificate for local development

CERT_DIR="./certs"
KEYSTORE_FILE="$CERT_DIR/keystore.p12"
PASSWORD="changeit"
HOSTNAME="localhost"
VALIDITY_DAYS=365

mkdir -p "$CERT_DIR"

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Certificate already exists at $KEYSTORE_FILE"
    read -p "Do you want to regenerate it? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 0
    fi
    rm "$KEYSTORE_FILE"
fi

echo "Generating self-signed certificate..."

keytool -genkeypair \
    -alias server \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE_FILE" \
    -validity $VALIDITY_DAYS \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=$HOSTNAME, OU=Development, O=Local, L=Local, ST=Local, C=US" \
    -ext "SAN=dns:$HOSTNAME,dns:localhost,ip:127.0.0.1"

if [ $? -eq 0 ]; then
    echo "✓ Certificate generated successfully at: $KEYSTORE_FILE"
    echo ""
    echo "Configuration:"
    echo "  Password: $PASSWORD"
    echo "  Hostname: $HOSTNAME"
    echo "  Valid for: $VALIDITY_DAYS days"
    echo ""
    echo "To use this certificate, set these environment variables:"
    echo "  TLS_ENABLED=true"
    echo "  TLS_KEYSTORE_PATH=$KEYSTORE_FILE"
    echo "  TLS_KEYSTORE_PASSWORD=$PASSWORD"
    echo ""
    echo "To trust this certificate in your browser:"
    echo "  1. Navigate to https://localhost:8080"
    echo "  2. Click 'Advanced' and 'Proceed to localhost (unsafe)'"
    echo "  3. Or import the certificate into your system's trust store"
else
    echo "✗ Failed to generate certificate"
    exit 1
fi

