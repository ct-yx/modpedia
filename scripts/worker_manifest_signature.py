#!/usr/bin/env python3
"""Worker manifest canonical payload and OpenSSL Ed25519 helpers.

The release workflow signs the manifest payload, never the JSON file or the JAR
bytes.  The format intentionally mirrors client-core.WorkerManifest exactly.
"""

from __future__ import annotations

import base64
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any


def canonical_payload(manifest: dict[str, Any]) -> bytes:
    """Return the UTF-8/LF payload used by client-core for Ed25519 signing."""

    artifact = manifest.get("artifact")
    if not isinstance(artifact, dict):
        raise ValueError("manifest.artifact must be an object")
    required = ("schema", "channel", "worker_version", "java_major", "protocol_version")
    missing = [key for key in required if key not in manifest]
    if missing:
        raise ValueError(f"manifest missing keys: {missing}")
    for key in ("primary_url", "fallback_urls", "sha256"):
        if key not in artifact:
            raise ValueError(f"artifact missing {key}")
    fallbacks = artifact["fallback_urls"]
    if not isinstance(fallbacks, list) or any(not isinstance(url, str) for url in fallbacks):
        raise ValueError("artifact.fallback_urls must be an array of strings")

    lines = [
        ("schema", str(manifest["schema"])),
        ("channel", str(manifest["channel"])),
        ("worker_version", str(manifest["worker_version"])),
        ("java_major", str(manifest["java_major"])),
        ("protocol_version", str(manifest["protocol_version"])),
        ("primary_url", str(artifact["primary_url"])),
    ]
    lines.extend(("fallback_url", url) for url in fallbacks)
    lines.append(("sha256", str(artifact["sha256"])))
    for name, value in lines:
        if "\r" in value or "\n" in value:
            raise ValueError(f"{name} must not contain a line break")
    return "".join(f"{name}={value}\n" for name, value in lines).encode("utf-8")


def _key_material(value: str) -> tuple[bytes, str]:
    """Decode PEM or Base64 DER key material and return (bytes, keyform)."""

    text = value.strip()
    if not text:
        raise ValueError("key material is empty")
    if text.startswith("-----BEGIN "):
        return text.encode("utf-8") + b"\n", "PEM"
    try:
        return base64.b64decode("".join(text.split()), validate=True), "DER"
    except ValueError as exc:
        raise ValueError("key material must be PEM or Base64 DER") from exc


def _openssl(operation: str, payload: bytes, key_text: str, signature: bytes | None = None) -> bytes | None:
    key_bytes, keyform = _key_material(key_text)
    with tempfile.TemporaryDirectory(prefix="modpedia-worker-signature-") as directory:
        root = Path(directory)
        key_path = root / "key"
        payload_path = root / "payload"
        signature_path = root / "signature"
        key_path.write_bytes(key_bytes)
        os.chmod(key_path, 0o600)
        payload_path.write_bytes(payload)
        command = ["openssl", "pkeyutl", operation, "-rawin", "-keyform", keyform,
                   "-inkey", str(key_path), "-in", str(payload_path)]
        if operation == "-verify":
            command.insert(3, "-pubin")
            if signature is None:
                raise ValueError("signature is required for verification")
            signature_path.write_bytes(signature)
            command.extend(["-sigfile", str(signature_path)])
        else:
            command.extend(["-out", str(signature_path)])
        result = subprocess.run(command, check=False, capture_output=True)
        if result.returncode != 0:
            detail = result.stderr.decode("utf-8", "replace").strip()
            raise RuntimeError(f"openssl Ed25519 {operation[1:]} failed: {detail}")
        if operation == "-sign":
            return signature_path.read_bytes()
        return None


def sign_payload(payload: bytes, private_key: str) -> str:
    """Sign payload with a configured Ed25519 private key and return Base64."""

    signature = _openssl("-sign", payload, private_key)
    assert signature is not None
    return base64.b64encode(signature).decode("ascii")


def verify_payload(payload: bytes, signature_base64: str, public_key: str) -> None:
    """Verify a Base64 Ed25519 signature with a configured public key."""

    try:
        signature = base64.b64decode(signature_base64, validate=True)
    except ValueError as exc:
        raise ValueError("manifest signature must be Base64") from exc
    _openssl("-verify", payload, public_key, signature)
