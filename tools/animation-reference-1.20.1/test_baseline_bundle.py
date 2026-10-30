"""检查冻结包的信任根、解析和有界读取，语义正反例另由真实Java执行"""

import gzip
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import baseline_bundle as bundle


class BaselineBoundaryTest(unittest.TestCase):
    def test_duplicate_keys_and_nonfinite_values_are_rejected(self):
        for raw in (b'{"schema":1,"schema":2}', b'{"value":NaN}', b'{"value":Infinity}', b'{"value":1e999}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                bundle.strict_json(raw)

    def test_public_urls_are_allowed_but_absolute_local_paths_are_rejected(self):
        bundle.public_strings({"source": "https://example.org/model", "file": "captures/head-0.json.gz"})
        private = "Z" + ":" + chr(92) + "private" + chr(92) + "workspace"
        with self.assertRaisesRegex(ValueError, "value omitted"):
            bundle.public_strings({"nested": [private]})

    def test_relative_file_rejects_traversal_and_noncanonical_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(bundle.relative_file(root, "captures/one.json.gz"), root / "captures/one.json.gz")
            for name in ("../escape", "captures/../../escape", "captures//one", "./one", "captures\\one", ""):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    bundle.relative_file(root, name)

    def test_gzip_expansion_is_bounded_by_actual_bytes(self):
        raw = gzip.compress(b"x" * 2048, mtime=0)
        with patch.object(bundle, "MAX_FILE", 1024), self.assertRaisesRegex(ValueError, "Expanded capture"):
            bundle.unpack_sample(raw)

    def test_manifest_requires_an_external_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = bundle.json_bytes({"schema": bundle.SCHEMA, "upstreamCommit": bundle.BASE})
            (root / "manifest.json").write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "externally approved"):
                bundle.verify_bundle(root, "")
            with self.assertRaisesRegex(ValueError, "approved digest"):
                bundle.verify_bundle(root, "0" * 64)

    def test_unknown_schema_is_rejected_even_when_digest_matches(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = bundle.json_bytes({"schema": "unreviewed-bundle", "upstreamCommit": bundle.BASE})
            (root / "manifest.json").write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "Unknown baseline schema"):
                bundle.verify_bundle(root, bundle.digest(raw))

    def test_payload_corruption_and_boolean_size_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"original"
            (root / "sample.bin").write_bytes(b"changed!")
            entry = {"path": "sample.bin", "kind": "canonical_asset", "bytes": len(payload), "sha256": bundle.digest(payload)}
            manifest = {"schema": bundle.SCHEMA, "upstreamCommit": bundle.BASE, "files": [entry]}
            raw = bundle.json_bytes(manifest)
            (root / "manifest.json").write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "approved bytes"):
                bundle.verify_bundle(root, bundle.digest(raw))
            entry["bytes"] = True
            raw = bundle.json_bytes(manifest)
            (root / "manifest.json").write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "invalid size"):
                bundle.verify_bundle(root, bundle.digest(raw))

    def test_historical_capture_cannot_be_promoted_by_an_inventory(self):
        inventory = bundle.json_bytes({"schema": "reference-toolchain-inventory-v1", "status": "INVENTORY_ONLY"})
        with self.assertRaisesRegex(ValueError, "inventory is not approval"):
            bundle.reference_identity({}, inventory)
        verified_build = bundle.json_bytes({"schema": "reference-build-identity-v1", "status": "LOCK_VERIFIED"})
        with self.assertRaisesRegex(ValueError, "runtime identity"):
            bundle.reference_identity({}, verified_build)


if __name__ == "__main__":
    unittest.main()
