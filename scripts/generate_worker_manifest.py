#!/usr/bin/env python3
"""从实际 Worker JAR 生成 Pages latest.json。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--primary-url", required=True)
    parser.add_argument("--fallback-url", action="append", default=[])
    parser.add_argument("--signature", default="")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    digest = hashlib.sha256(args.worker.read_bytes()).hexdigest()
    manifest = {
        "schema": 1,
        "channel": "stable",
        "worker_version": args.version,
        "java_major": 17,
        "protocol_version": 2,
        "artifact": {
            "primary_url": args.primary_url,
            "fallback_urls": args.fallback_url,
            "sha256": digest,
            "signature": args.signature or None,
        },
        "integrity_status": "signed" if args.signature else "unsigned",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
