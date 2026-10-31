#!/usr/bin/env python3
"""验证 Worker 和主模组发布物的边界，不依赖第三方 Python 包。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path


WORKER_MAIN = "io/ctyx/modpedia/worker/WorkerMain.class"
FORBIDDEN_WORKER_PREFIXES = (
    "net/minecraft/",
    "net/minecraftforge/",
    "net/neoforged/",
    "io/ctyx/modpedia/client/",
)
FORBIDDEN_MOD_PREFIXES = (
    "io/ctyx/modpedia/worker/",
    "META-INF/modpedia-worker/",
)


def fail(message: str) -> None:
    raise SystemExit(f"release artifact validation failed: {message}")


def entries(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.namelist()
    except (OSError, zipfile.BadZipFile) as exc:
        fail(f"{path}: invalid JAR: {exc}")


def class_major(path: Path, entry: str) -> int:
    try:
        with zipfile.ZipFile(path) as archive:
            data = archive.read(entry)
    except KeyError:
        fail(f"{path}: missing {entry}")
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        fail(f"{path}: {entry} is not a class file")
    return struct.unpack(">H", data[6:8])[0]


def validate_worker(path: Path, expected_major: int) -> None:
    names = entries(path)
    if WORKER_MAIN not in names:
        fail(f"{path}: missing WorkerMain")
    actual = class_major(path, WORKER_MAIN)
    if actual != expected_major:
        fail(f"{path}: WorkerMain class major {actual}, expected {expected_major}")
    forbidden = [name for name in names if name.startswith(FORBIDDEN_WORKER_PREFIXES)]
    if forbidden:
        fail(f"{path}: Worker contains game/client classes: {forbidden[:5]}")


def validate_mod(path: Path) -> None:
    forbidden = [name for name in entries(path) if name.startswith(FORBIDDEN_MOD_PREFIXES)]
    if forbidden:
        fail(f"{path}: main mod contains Worker payload: {forbidden[:5]}")


def validate_manifest(path: Path) -> None:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"{path}: invalid JSON: {exc}")
    required = ("schema", "channel", "worker_version", "java_major", "protocol_version", "artifact")
    missing = [key for key in required if key not in manifest]
    if missing:
        fail(f"{path}: missing keys: {missing}")
    if manifest["schema"] != 1 or manifest["java_major"] != 17 or manifest["protocol_version"] != 2:
        fail(f"{path}: schema/java/protocol does not match Worker v2 contract")
    artifact = manifest["artifact"]
    for key in ("primary_url", "fallback_urls", "sha256", "signature"):
        if key not in artifact:
            fail(f"{path}: artifact missing {key}")
    if not re.match(r"^https?://", artifact["primary_url"]):
        fail(f"{path}: artifact.primary_url is not an HTTP(S) URL")
    if not isinstance(artifact["fallback_urls"], list):
        fail(f"{path}: artifact.fallback_urls must be an array")
    integrity_pending = manifest.get("integrity_status") == "pending-release"
    if not integrity_pending:
        if not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"] or ""):
            fail(f"{path}: sha256 must be a lowercase SHA-256 digest")
        if not artifact["signature"]:
            fail(f"{path}: signature is required for a released manifest")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", type=Path)
    parser.add_argument("--mod", action="append", type=Path, default=[])
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--worker-major", type=int, default=61)
    args = parser.parse_args()
    if args.worker:
        validate_worker(args.worker, args.worker_major)
    for mod in args.mod:
        validate_mod(mod)
    if args.manifest:
        validate_manifest(args.manifest)
    print("release artifact validation: OK")


if __name__ == "__main__":
    main()
