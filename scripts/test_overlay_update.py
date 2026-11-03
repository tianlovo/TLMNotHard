"""Scoped updater contract checks; every mutation stays in a fresh temporary layout."""
import io
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from distribution import update as u
import package_overlay as packaging


def jar(value):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("mcmod.info", json.dumps({"modList": [{"modid": "tlmnh", "mcversion": "1.7.10", "version": value}]}))
        archive.writestr("fixture.txt", value)
    return output.getvalue()


class OverlayUpdateTest(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(os.environ.get("TLMNH_UPDATE_TEST_ROOT",
                              str(Path(__file__).resolve().parents[2] / "cache/validation/overlay-update-tests"))).resolve()
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="tlmnh-overlay-", dir=temporary_root)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def target(self, kind):
        target = self.root / kind
        target.mkdir()
        (target / ("mmc-pack.json" if kind == "client" else "lwjgl3ify-forgePatches.jar")).write_bytes(b"original marker")
        foreign = {".gtnh-managed-" + kind + ".json": b'{"other.jar":"keep exact bytes"}',
                   (".minecraft/" if kind == "client" else "") + "config/user.cfg": b"unknown-user-options\r\n",
                   u.mod_prefix(kind) + "other.jar": b"unrelated mod"}
        for name, content in foreign.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        return target, foreign

    def package(self, kind, version, old=None):
        package = self.root / (kind + "-package-" + version)
        package.mkdir()
        name = u.mod_prefix(kind) + "tlmnh-" + version + ".jar"
        docs = ".tlmnh-distribution/" + kind + "/NOTICE.txt"
        payload = {name: jar(version), docs: ("source " + version).encode()}
        for path, data in payload.items():
            dst = package / path
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(data)
        manifest = dict(schema=u.SCHEMA, scope="tlmnh", kind=kind, patches={}, channel="local-validation",
                        distributable=False, status="local-validation", files={n: u.digest(b) for n, b in payload.items()},
                        jar=dict(path=name, sha256=u.digest(payload[name])), remove_files=old or {}, replace_files={})
        (package / "manifest.json").write_bytes(u.encoded(manifest))
        return package, manifest

    def unchanged(self, target, foreign):
        for name, content in foreign.items():
            self.assertEqual((target / name).read_bytes(), content)

    def test_client_isolated_upgrade_idempotence_and_roundtrip(self):
        target, foreign = self.target("client")
        old = ".minecraft/mods/tlmnh-old.jar"
        (target / old).write_bytes(jar("old"))
        package, manifest = self.package("client", "new", {old: [u.sha(target / old)]})
        backup = u.apply(package, target / ".minecraft")
        self.assertFalse((target / old).exists())
        self.assertTrue((target / manifest["jar"]["path"]).exists())
        self.unchanged(target, foreign)
        self.assertIsNone(u.apply(package, target))
        receipt = json.loads((backup / "receipt.json").read_bytes())
        self.assertEqual(receipt["status"], "applied")
        u.restore(target, backup)
        u.restore(target, backup)
        self.assertEqual((target / old).read_bytes(), jar("old"))
        self.assertFalse((target / manifest["jar"]["path"]).exists())
        self.assertFalse((target / ".tlmnh-managed-client.json").exists())
        self.unchanged(target, foreign)

    def test_server_same_name_sha_replace_and_unknown_successor(self):
        target, foreign = self.target("server")
        package, manifest = self.package("server", "same")
        name = manifest["jar"]["path"]
        original = jar("old-bytes")
        (target / name).write_bytes(original)
        with self.assertRaises(ValueError):
            u.apply(package, target)
        manifest["replace_files"] = {name: [u.digest(original)]}
        (package / "manifest.json").write_bytes(u.encoded(manifest))
        backup = u.apply(package, target)
        (target / name).write_bytes(b"unknown successor")
        with self.assertRaises(ValueError):
            u.restore(target, backup)
        self.assertEqual((target / name).read_bytes(), b"unknown successor")
        self.unchanged(target, foreign)

    def test_unknown_renamed_mod_and_bad_payload_are_preserved(self):
        target, foreign = self.target("server")
        package, manifest = self.package("server", "new")
        renamed = target / "mods/renamed-maid.jar"
        renamed.write_bytes(jar("unknown"))
        with self.assertRaises(ValueError):
            u.apply(package, target)
        self.assertEqual(renamed.read_bytes(), jar("unknown"))
        renamed.unlink()
        (package / manifest["jar"]["path"]).write_bytes(b"damaged download")
        with self.assertRaises(ValueError):
            u.apply(package, target)
        self.assertFalse((target / ".tlmnh-managed-server.json").exists())
        self.unchanged(target, foreign)

    def test_mid_apply_failure_rolls_back_complete_changed_set(self):
        target, foreign = self.target("server")
        old = "mods/tlmnh-old.jar"
        original = jar("old")
        (target / old).write_bytes(original)
        package, manifest = self.package("server", "new", {old: [u.digest(original)]})
        native_replace = u.os.replace
        failed = []
        def fail_once(src, dst):
            if Path(dst) == target / manifest["jar"]["path"] and not failed:
                failed.append(True)
                raise OSError("controlled replacement failure")
            return native_replace(src, dst)
        with mock.patch.object(u.os, "replace", side_effect=fail_once):
            with self.assertRaises(OSError):
                u.apply(package, target)
        self.assertEqual((target / old).read_bytes(), original)
        self.assertFalse((target / manifest["jar"]["path"]).exists())
        self.assertFalse((target / ".tlmnh-managed-server.json").exists())
        receipts = list((target / ".tlmnh-update-backups/server").glob("*/receipt.json"))
        self.assertEqual(len(receipts), 1)
        self.assertEqual(json.loads(receipts[0].read_bytes())["status"], "rolled-back")
        self.unchanged(target, foreign)

    def test_newer_update_blocks_older_restore_until_reverse_order(self):
        target, foreign = self.target("server")
        first, _ = self.package("server", "one")
        second, second_manifest = self.package("server", "two")
        old_backup = u.apply(first, target)
        new_backup = u.apply(second, target)
        with self.assertRaises(ValueError):
            u.restore(target, old_backup)
        self.assertTrue((target / second_manifest["jar"]["path"]).exists())
        u.restore(target, new_backup)
        u.restore(target, old_backup)
        self.assertFalse((target / ".tlmnh-managed-server.json").exists())
        self.unchanged(target, foreign)

    def test_interrupted_write_retains_prepared_backup_for_explicit_restore(self):
        target, foreign = self.target("server")
        old = "mods/tlmnh-old.jar"
        (target / old).write_bytes(jar("old"))
        package, manifest = self.package("server", "new", {old: [u.sha(target / old)]})
        native_replace = u.os.replace
        def interrupt_after_write(src, dst):
            result = native_replace(src, dst)
            if Path(dst) == target / manifest["jar"]["path"]:
                # Controlled caller interruption, not a real process/power-loss simulation.
                raise KeyboardInterrupt("controlled interruption after native replace")
            return result
        with mock.patch.object(u.os, "replace", side_effect=interrupt_after_write):
            with self.assertRaises(KeyboardInterrupt):
                u.apply(package, target)
        pending = u.pending_backups(target, "server")
        self.assertEqual(len(pending), 1)
        with self.assertRaises(ValueError):
            u.apply(package, target)
        u.restore(target, pending[0])
        self.assertEqual((target / old).read_bytes(), jar("old"))
        self.assertFalse((target / manifest["jar"]["path"]).exists())
        self.unchanged(target, foreign)

    def test_windows_aliases_and_escape_are_rejected_before_write(self):
        target, foreign = self.target("client")
        for name in ("../outside", ".minecraft/mods/CON.jar", ".minecraft/mods/bad.jar.", "mods\\bad.jar", "mods/file:stream"):
            with self.assertRaises(ValueError):
                u.safe(target, name)
        self.unchanged(target, foreign)

    @unittest.skipUnless(os.name == "nt", "The recorded process identity guard targets the actual Windows runtime")
    def test_selected_live_process_and_reused_pid_boundary(self):
        target, foreign = self.target("server")
        metadata = target / "process.json"
        state = dict(side="server", cwd=str(target), java=os.getpid(), supervisor=os.getpid(),
                     started_at=datetime.now(timezone.utc).isoformat())
        metadata.write_bytes(u.encoded(state))
        with self.assertRaisesRegex(ValueError, "仍在运行"):
            u.require_stopped(target, "server")  # Query this test process only, with its real creation FILETIME.
        state["started_at"] = "1999-12-31T23:59:00+00:00"
        state["finished_at"] = "2000-01-01T00:00:00+00:00"
        metadata.write_bytes(u.encoded(state))
        u.require_stopped(target, "server")  # A newer process with the reused PID is not the recorded launch.
        state["cwd"] = str(self.root / "another-target")
        metadata.write_bytes(u.encoded(state))
        with mock.patch.object(u, "process_created_if_running") as query:
            u.require_stopped(target, "server")
            query.assert_not_called()
        self.unchanged(target, foreign)

    def test_second_archive_failure_cleanup_and_exact_retry(self):
        output = self.root / "archives"
        results = {"client": ("client.zip", jar("client")), "server": ("server.zip", jar("server"))}
        publish = packaging.exclusive_publish
        def fail_server(src, dst):
            if Path(dst).name == "server.zip":
                raise OSError("controlled second-archive failure")
            return publish(src, dst)
        with mock.patch.object(packaging, "exclusive_publish", side_effect=fail_server):
            with self.assertRaises(OSError):
                packaging.publish_archives(output, results)
        self.assertFalse((output / "client.zip").exists())
        self.assertFalse((output / "server.zip").exists())
        packaging.publish_archives(output, results)
        client_identity = (output / "client.zip").stat().st_ino
        packaging.publish_archives(output, results)
        (output / "server.zip").unlink()
        packaging.publish_archives(output, results)  # An exact complete first archive can be reused after interruption.
        self.assertEqual((output / "client.zip").stat().st_ino, client_identity)
        self.assertEqual((output / "server.zip").read_bytes(), results["server"][1])

    def test_archive_failure_retains_unknown_successor(self):
        output = self.root / "archives"
        results = {"client": ("client.zip", jar("client")), "server": ("server.zip", jar("server"))}
        publish = packaging.exclusive_publish
        def replace_then_fail(src, dst):
            if Path(dst).name == "server.zip":
                (output / "client.zip").write_bytes(b"unknown successor")
                raise OSError("controlled successor during publication")
            return publish(src, dst)
        with mock.patch.object(packaging, "exclusive_publish", side_effect=replace_then_fail):
            with self.assertRaises(OSError):
                packaging.publish_archives(output, results)
        self.assertEqual((output / "client.zip").read_bytes(), b"unknown successor")
        with self.assertRaises(ValueError):
            packaging.publish_archives(output, results)
        self.assertEqual((output / "client.zip").read_bytes(), b"unknown successor")


if __name__ == "__main__":
    unittest.main()
