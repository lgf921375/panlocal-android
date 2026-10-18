#!/usr/bin/env python3
"""Personalize a CI-built debug APK without publishing its credential.

USAGE:
  PANLOCAL_EMBEDDED_SECRET=<32 ASCII characters> python3 scripts/personalize_apk.py input.apk unsigned.apk

Then zipalign and sign with Android SDK apksigner (do not distribute unsigned.apk).
No other APK contents are modified. The source repository contains only the placeholder.
"""
import argparse
import hashlib
import os
import struct
import sys
import zipfile
import zlib

SLOT = b"Q" * 32
DEX = "classes2.dex"

def patch_dex(data: bytes, key: bytes) -> bytes:
    if not data.startswith(b"dex\n"):
        raise ValueError("DEX missing or invalid header")
    if len(key) != len(SLOT) or any(ch > 0x7e or ch < 0x21 for ch in key):
        raise ValueError("Credential must be 32 printable ASCII characters")
    if data.count(SLOT) != 1:
        raise ValueError("Expected exactly one owner credential slot")
    mutable = bytearray(data.replace(SLOT, key, 1))
    mutable[12:32] = hashlib.sha1(mutable[32:]).digest()
    struct.pack_into("<I", mutable, 8, zlib.adler32(mutable[12:]) & 0xffffffff)
    if hashlib.sha1(mutable[32:]).digest() != mutable[12:32]:
        raise ValueError("DEX signature validation failed")
    return bytes(mutable)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("unsigned_output")
    args = parser.parse_args()
    secret = os.environ.get("PANLOCAL_EMBEDDED_SECRET", "")
    if len(secret) != 32 or not secret.isascii() or not secret.isprintable():
        raise SystemExit("Missing/invalid 32-character credential in environment")
    if os.path.abspath(args.source) == os.path.abspath(args.unsigned_output):
        raise SystemExit("Must not overwrite input APK")
    with zipfile.ZipFile(args.source, "r") as src, zipfile.ZipFile(args.unsigned_output, "w") as out:
        if src.testzip():
            raise RuntimeError("Source APK has a corrupt ZIP entry")
        if DEX not in src.namelist():
            raise RuntimeError("Missing classes2.dex")
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == DEX:
                data = patch_dex(data, secret.encode("ascii"))
            out.writestr(item, data)
    with zipfile.ZipFile(args.unsigned_output, "r") as verify:
        dex = verify.read(DEX)
        if secret.encode("ascii") not in dex or SLOT in dex or verify.testzip():
            raise RuntimeError("Personalized APK integrity test failed")
    print("Personalized unsigned APK verified; must zipalign and sign with official apksigner")
    return 0

if __name__ == "__main__":
    sys.exit(main())
