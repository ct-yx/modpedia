#!/usr/bin/env python3
"""Offline canonical payload and RFC 8032 Ed25519 verification self-test."""

from __future__ import annotations

import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from worker_manifest_signature import canonical_payload, verify_payload


manifest = {
    "schema": 1,
    "channel": "stable",
    "worker_version": "2.0.0",
    "java_major": 17,
    "protocol_version": 2,
    "artifact": {
        "primary_url": "https://primary/worker.jar",
        "fallback_urls": ["https://backup/worker.jar"],
        "sha256": "0" * 64,
        "signature": "excluded-from-canonical-payload",
    },
}
expected = (
    "schema=1\nchannel=stable\nworker_version=2.0.0\n"
    "java_major=17\nprotocol_version=2\n"
    "primary_url=https://primary/worker.jar\n"
    "fallback_url=https://backup/worker.jar\n"
    + "sha256=" + ("0" * 64) + "\n"
).encode("utf-8")
assert canonical_payload(manifest) == expected

# RFC 8032 test vector 2 (single byte message). It tests Ed25519 verification
# without creating a key or putting any release key in the repository.
raw_public = base64.b64decode("PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw=")
spki_prefix = bytes.fromhex("302a300506032b6570032100")
public_key = base64.b64encode(spki_prefix + raw_public).decode("ascii")
signature = "kqAJqfDUyrhyDoILX2QlQKKye1QWUD+Ps3YiI+vbadoIWsHkPhWZbkWPNhPQ8R2MOHsurrQwKu6wDSkWErsMAA=="
verify_payload(bytes.fromhex("72"), signature, public_key)

tampered = bytearray(expected)
tampered[-2] ^= 1
try:
    verify_payload(bytes(tampered), signature, public_key)
except RuntimeError:
    pass
else:
    raise AssertionError("tampered canonical payload must fail Ed25519 verification")

print("Worker canonical payload and Ed25519 verifier self-test: OK")
