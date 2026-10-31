#!/usr/bin/env python3
"""Sign docs/site/worker/latest.json with the configured release key."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from worker_manifest_signature import canonical_payload, sign_payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--key-env", default="MODPEDIA_WORKER_SIGNING_KEY")
    args = parser.parse_args()
    private_key = os.environ.get(args.key_env, "")
    if not private_key.strip():
        raise SystemExit(f"缺少正式 Worker 签名密钥 Secret：{args.key_env}")
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        payload = canonical_payload(manifest)
        signature = sign_payload(payload, private_key)
    except (OSError, json.JSONDecodeError, ValueError, RuntimeError) as exc:
        raise SystemExit(f"Worker 清单签名失败：{exc}") from exc
    artifact = manifest.setdefault("artifact", {})
    artifact["signature"] = signature
    manifest["integrity_status"] = "signed"
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Worker manifest signed: {args.manifest}")


if __name__ == "__main__":
    main()
