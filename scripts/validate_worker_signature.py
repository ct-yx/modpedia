#!/usr/bin/env python3
"""Cryptographically validate the Worker manifest Ed25519 signature."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from worker_manifest_signature import canonical_payload, verify_payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--public-key-env", default="MODPEDIA_WORKER_PUBLIC_KEY")
    args = parser.parse_args()
    public_key = os.environ.get(args.public_key_env, "")
    if not public_key.strip():
        raise SystemExit(f"缺少正式 Worker 公钥 Secret/配置：{args.public_key_env}")
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        artifact = manifest.get("artifact", {})
        signature = artifact.get("signature")
        if manifest.get("integrity_status") != "signed" or not signature:
            raise ValueError("manifest must be signed before cryptographic validation")
        verify_payload(canonical_payload(manifest), signature, public_key)
    except (OSError, json.JSONDecodeError, ValueError, RuntimeError) as exc:
        raise SystemExit(f"Worker 清单 Ed25519 验签失败：{exc}") from exc
    print(f"Worker manifest Ed25519 signature: OK ({args.manifest})")


if __name__ == "__main__":
    main()
