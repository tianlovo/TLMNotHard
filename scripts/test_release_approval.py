"""Offline synthetic release-decision checks. No real artifact, person or audio permission is approved."""

import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import audit_candidate_distribution as audit
import package_overlay as packaging


class ReleaseApprovalTests(unittest.TestCase):
    def setUp(self):
        clock = mock.patch.object(audit, "utc_today", return_value=audit.date(2026, 11, 4))
        clock.start()
        self.addCleanup(clock.stop)
        self.test_root = Path(os.environ.get("TLMNH_RELEASE_TEST_ROOT",
                              str(Path(__file__).resolve().parents[2] / "cache/validation/release-approval-tests")))
        self.test_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="synthetic-only-", dir=self.test_root)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(self.test_root.resolve()))
        self.addCleanup(self.temporary.cleanup)
        # Use the selected full GPL text while all approval/person/evidence records remain synthetic.
        self.gpl = (audit.PROJECT / "LICENSE").read_bytes()
        (self.root / "LICENSE").write_bytes(self.gpl)
        project = mock.patch.object(audit, "PROJECT", self.root)
        project.start()
        self.addCleanup(project.stop)
        evidence = self.root / "synthetic-evidence.json"
        evidence.write_text('{"scope":"SYNTHETIC_UNIT_TEST_ONLY"}\n', encoding="utf-8")
        self.evidence = {"path": evidence.name, "sha256": audit.digest(evidence.read_bytes())}
        self.members = {
            "mcmod.info": json.dumps({"modListVersion": 2, "modList": [{"modid": "tlmnh", "version": "1.0.0",
                "license": "GPL-3.0-only"}]}).encode(),
            "META-INF/THIRD_PARTY_NOTICES.md": b"TLMNH original code license: GPL-3.0-only\nSynthetic attribution only\n",
            audit.ORIGINAL_LICENSE_MEMBER: self.gpl,
            "assets/tlmnh/builtin-sounds-provenance.json": b'{"audio_distribution_cleared":false}',
            "assets/tlmnh/builtin-models.json": b"{}", "assets/tlmnh/builtin-sounds.json": b"{}",
        }
        for name in audit.LICENSES:
            self.members["META-INF/licenses/" + name] = b"synthetic retained third-party text"
        authority = mock.patch.object(audit, "SOUND_MANIFEST_SHA256", audit.digest(self.members[audit.SOUND_MANIFEST]))
        authority.start()
        self.addCleanup(authority.stop)
        self.data = self.archive(self.members)
        self.binding = {"candidate_sha256": audit.digest(self.data), "snapshot_sha256": "2" * 64,
                        "main_source_files": 1}
        self.documents = {}
        for name in ("SOURCE_PROVENANCE.md", "docs/USER_GUIDE.md", "docs/USER_GUIDE.en.md",
                     "scripts/distribution/update.py", "scripts/distribution/README.md"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(("synthetic " + name).encode())
            self.documents[name] = audit.digest(target.read_bytes())
        self.documents["requirements.txt"] = audit.digest(b"rich\n")
        self.report = {"schema": "candidate-distribution-audit-v1", "scope": "local-validation-only",
                       "distributable": False, "integrity_passed": True, "release_acceptance_evaluated": False,
                       "sha256": audit.digest(self.data), "checks": {
                           "metadata": {"passed": True, "observed": {"version": "1.0.0", "license": audit.PROJECT_LICENSE}},
                           "original_code_license": {"passed": True, "observed": {
                               "status": "declared", "spdx": audit.PROJECT_LICENSE}},
                           "licenses_and_source_anchors": {"passed": True, "observed": {
                               "provenance_manifests": ["assets/tlmnh/builtin-sounds-provenance.json"]}}},
                       "original_source_license": audit.PROJECT_LICENSE,
                       "release_blockers": [{"code": "audio_publication_not_evaluated"}]}
        self.approval = {"schema": "tlmnh-release-approval-v2", "decision": "approved",
                         "approved_by": "SYNTHETIC_UNIT_TEST_NOT_A_PERSON", "approved_on": "2026-11-04",
                         "candidate": {"version": "1.0.0", "sha256": audit.digest(self.data),
                                       "source_snapshot_sha256": self.binding["snapshot_sha256"]},
                         "package_documents_sha256": self.documents,
                         "attribution": {"notice_sha256": audit.digest(self.members["META-INF/THIRD_PARTY_NOTICES.md"]),
                             "provenance_sha256": {"assets/tlmnh/builtin-sounds-provenance.json":
                                 audit.digest(self.members["assets/tlmnh/builtin-sounds-provenance.json"])},
                             "original_code_license": {"spdx": audit.PROJECT_LICENSE, "member": audit.ORIGINAL_LICENSE_MEMBER,
                                                       "sha256": audit.digest(self.members[audit.ORIGINAL_LICENSE_MEMBER])}},
                         "audio_packs": {name: {"decision": "approved", "basis": "Synthetic test only",
                             "publication_steps": ["Synthetic step, no actual author contact"],
                             "evidence": self.evidence} for name in audit.AUDIO_PACKS},
                         "acceptance": {name: {"decision": "accepted", "assessment": "Synthetic bounded test only",
                             "known_limitations": ["No real release was reviewed"], "evidence": [self.evidence]}
                             for name in audit.RELEASE_ACCEPTANCE}}
        self.approval["acceptance"]["benchmark"].update(
            covered_classes=["B%02d" % n for n in range(1, 15)], noninferiority_delta=None)
        self.audio_receipts = {}
        for name in audit.AUDIO_PACKS:
            receipt = {"schema": "tlmnh-audio-publication-evidence-v1", "decision": "approved", "pack": name,
                       "candidate": dict(self.approval["candidate"]),
                       "provenance_sha256": audit.digest(self.members[audit.SOUND_MANIFEST]),
                       "terms": {"url": "https://example.invalid/SYNTHETIC_ONLY", "revision": "synthetic",
                           "language": "en", "document": self.evidence, "extractable_archives": "permitted",
                           "applicability": {"decision": "applicable", "assessment": "Synthetic test only",
                                             "evidence": self.evidence}},
                       "authorization": {"decision": "authorized", "evidence": self.evidence},
                       "attribution": {"decision": "completed", "evidence": self.evidence},
                       "obligations": [{"id": "synthetic-credit", "action": "Synthetic credit check",
                           "clause": "Synthetic clause", "timing": "before_release", "status": "completed",
                           "completed_on": "2026-11-04", "evidence": self.evidence}]}
            self.audio_receipts[name] = receipt
            self.write_audio_receipt(name, receipt)
        self.path = self.root / "synthetic-approval.json"

    def write_audio_receipt(self, name, receipt, approval=None):
        target = self.root / (name + "-synthetic-audio.json")
        target.write_text(json.dumps(receipt), encoding="utf-8")
        selected = self.approval if approval is None else approval
        selected["audio_packs"][name]["evidence"] = {"path": target.name, "sha256": audit.digest(target.read_bytes())}

    @staticmethod
    def archive(members):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, value in members.items():
                archive.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), value)
        return stream.getvalue()

    def evaluate(self, approval=None, report=None, data=None):
        self.path.write_text(json.dumps(self.approval if approval is None else approval), encoding="utf-8")
        return audit.approve_release(self.report if report is None else report,
                                     self.data if data is None else data, self.path, self.binding, self.documents)

    def test_missing_empty_or_pending_approval_is_rejected(self):
        with self.assertRaises(ValueError):
            audit.approve_release(self.report, self.data, None, self.binding, self.documents)
        with self.assertRaises(ValueError):
            self.evaluate({})
        approval = copy.deepcopy(self.approval)
        approval["decision"] = "pending"
        with self.assertRaises(ValueError):
            self.evaluate(approval)

    def test_wrong_candidate_version_snapshot_or_sidecar_cannot_reuse_approval(self):
        for field in ("version", "sha256", "source_snapshot_sha256"):
            with self.subTest(field=field):
                approval = copy.deepcopy(self.approval)
                approval["candidate"][field] = "wrong"
                with self.assertRaises(ValueError):
                    self.evaluate(approval)
        approval = copy.deepcopy(self.approval)
        approval["package_documents_sha256"]["docs/USER_GUIDE.md"] = "0" * 64
        with self.assertRaises(ValueError):
            self.evaluate(approval)
        report = copy.deepcopy(self.report)
        report["checks"]["metadata"]["observed"]["version"] = "0.1.0-indev.203"
        with self.assertRaises(ValueError):
            self.evaluate(report=report)

    def test_resource_license_or_integrity_failure_cannot_be_waived(self):
        members = dict(self.members)
        del members[audit.ORIGINAL_LICENSE_MEMBER]
        data = self.archive(members)
        approval = copy.deepcopy(self.approval)
        approval["candidate"]["sha256"] = audit.digest(data)
        report, binding = copy.deepcopy(self.report), dict(self.binding)
        report["sha256"] = binding["candidate_sha256"] = audit.digest(data)
        self.path.write_text(json.dumps(approval), encoding="utf-8")
        with self.assertRaises(ValueError):
            audit.approve_release(report, data, self.path, binding, self.documents)
        report = copy.deepcopy(self.report)
        report["integrity_passed"] = False
        with self.assertRaises(ValueError):
            self.evaluate(report=report)
        members = dict(self.members)
        members["assets/tlmnh/builtin-sounds-provenance.json"] = b'{"audio_distribution_cleared":true}'
        data = self.archive(members)
        approval = copy.deepcopy(self.approval)
        approval["candidate"]["sha256"] = audit.digest(data)
        approval["attribution"]["provenance_sha256"]["assets/tlmnh/builtin-sounds-provenance.json"] = audit.digest(
            members["assets/tlmnh/builtin-sounds-provenance.json"])
        report, binding = copy.deepcopy(self.report), dict(self.binding)
        report["sha256"] = binding["candidate_sha256"] = audit.digest(data)
        self.path.write_text(json.dumps(approval), encoding="utf-8")
        with self.assertRaises(ValueError):
            audit.approve_release(report, data, self.path, binding, self.documents)
        approval = copy.deepcopy(self.approval)
        approval["attribution"]["provenance_sha256"] = {}
        with self.assertRaises(ValueError):
            self.evaluate(approval)

    def test_fixed_false_source_is_approved_only_by_candidate_bound_audio_receipts(self):
        self.assertFalse(json.loads(self.members[audit.SOUND_MANIFEST])["audio_distribution_cleared"])
        self.assertTrue(self.evaluate()["distributable"])
        approval = copy.deepcopy(self.approval)
        for decision in approval["audio_packs"].values():
            decision["evidence"] = self.evidence
        with self.assertRaisesRegex(ValueError, "Structured approved audio"):
            self.evaluate(approval)
        approval["schema"] = "tlmnh-release-approval-v1"
        with self.assertRaisesRegex(ValueError, "unrecognized"):
            self.evaluate(approval)

    def test_audio_receipt_candidate_source_state_and_proofs_cannot_be_substituted(self):
        name = "touhou_little_maid"
        original = self.audio_receipts[name]
        changes = ("candidate", "source", "pack", "decision", "terms", "applicability", "authorization",
                   "attribution", "terms_proof", "applicability_proof", "authorization_proof", "attribution_proof")
        for change in changes:
            with self.subTest(change=change):
                receipt = copy.deepcopy(original)
                if change == "candidate":
                    receipt["candidate"]["sha256"] = "0" * 64
                elif change == "source":
                    receipt["provenance_sha256"] = "0" * 64
                elif change in ("pack", "decision"):
                    receipt[change] = "wrong"
                elif change == "terms":
                    receipt["terms"]["extractable_archives"] = "pending"
                elif change == "applicability":
                    receipt["terms"]["applicability"]["decision"] = "pending"
                elif change in ("authorization", "attribution"):
                    receipt[change]["decision"] = "pending"
                else:
                    field = change.removesuffix("_proof")
                    if field == "terms":
                        proof = receipt["terms"]["document"]
                    elif field == "applicability":
                        proof = receipt["terms"]["applicability"]["evidence"]
                    else:
                        proof = receipt[field]["evidence"]
                    proof["sha256"] = "0" * 64
                self.write_audio_receipt(name, receipt)
                with self.assertRaises(ValueError):
                    self.evaluate()
        self.write_audio_receipt(name, original)
        receipt_path = self.root / self.approval["audio_packs"][name]["evidence"]["path"]
        receipt_path.write_bytes(b"changed audio receipt")
        with self.assertRaises(ValueError):
            self.evaluate()
        receipt_path.unlink()
        with self.assertRaises(FileNotFoundError):
            self.evaluate()

    def test_japanese_terms_require_actual_language_qualification(self):
        name = "touhou_little_maid"
        receipt = copy.deepcopy(self.audio_receipts[name])
        receipt["terms"].update(url="https://amitaro.net/voice/voice_rule/", language="ja")
        self.write_audio_receipt(name, receipt)
        with self.assertRaisesRegex(ValueError, "Japanese comprehension"):
            self.evaluate()
        receipt["terms"]["applicability"]["japanese_comprehension"] = "confirmed"
        self.write_audio_receipt(name, receipt)
        self.assertTrue(self.evaluate()["distributable"])
        receipt["terms"]["language"] = "en"
        self.write_audio_receipt(name, receipt)
        with self.assertRaisesRegex(ValueError, "Japanese comprehension"):
            self.evaluate()

    def test_before_release_obligations_and_post_publication_deadlines_are_enforced(self):
        name = "touhou_little_maid"
        receipt = copy.deepcopy(self.audio_receipts[name])
        pending = {"id": "synthetic-report", "action": "Synthetic unsent report", "clause": "Synthetic post-publication clause",
                   "timing": "after_publication", "status": "pending", "due_within_days": 30, "published_on": None}
        receipt["obligations"].append(pending)
        self.write_audio_receipt(name, receipt)
        result = self.evaluate()
        steps = result["release_approval"]["audio_publication"][name]["obligations"]
        self.assertEqual(steps[-1]["status"], "pending")
        self.assertNotIn("evidence_sha256", steps[-1])
        pending["published_on"] = "2026-11-01"
        self.write_audio_receipt(name, receipt)
        self.assertTrue(self.evaluate()["distributable"])
        for change in ("before_release", "overdue", "future_publication", "no_deadline", "invented_sent", "future_completion"):
            with self.subTest(change=change):
                changed = copy.deepcopy(receipt)
                obligation = changed["obligations"][-1]
                if change == "before_release":
                    obligation["timing"] = "before_release"
                elif change == "overdue":
                    obligation["published_on"] = "2026-01-01"
                elif change == "future_publication":
                    obligation["published_on"] = "2027-01-01"
                elif change == "no_deadline":
                    obligation["due_within_days"] = 0
                elif change == "invented_sent":
                    obligation["evidence"] = self.evidence
                else:
                    changed["obligations"][0]["completed_on"] = "2027-01-01"
                self.write_audio_receipt(name, changed)
                with self.assertRaises(ValueError):
                    self.evaluate()

    def test_missing_changed_evidence_and_incomplete_acceptance_are_rejected(self):
        approval = copy.deepcopy(self.approval)
        approval["audio_packs"]["littlemaid_peco"]["decision"] = "pending"
        with self.assertRaises(ValueError):
            self.evaluate(approval)
        approval = copy.deepcopy(self.approval)
        del approval["acceptance"]["user_play"]
        with self.assertRaises(ValueError):
            self.evaluate(approval)
        (self.root / self.evidence["path"]).write_bytes(b"changed evidence")
        with self.assertRaises(ValueError):
            self.evaluate()
        (self.root / self.evidence["path"]).unlink()
        with self.assertRaises(FileNotFoundError):
            self.evaluate()

    def test_old_approval_cannot_publish_after_pending_obligation_expires(self):
        name = "touhou_little_maid"
        receipt = copy.deepcopy(self.audio_receipts[name])
        receipt["obligations"].append({"id": "synthetic-unsent-report", "action": "Synthetic unsent report",
            "clause": "Synthetic post-publication clause", "timing": "after_publication", "status": "pending",
            "due_within_days": 30, "published_on": "2026-11-01"})
        self.write_audio_receipt(name, receipt)
        # The original approval was timely. The actual evaluation clock governs every later package attempt.
        with mock.patch.object(audit, "utc_today", return_value=audit.date(2026, 12, 1)):
            self.assertTrue(self.evaluate()["distributable"])
        approval = copy.deepcopy(self.approval)
        approval["evaluated_on"] = "2026-11-04"
        with mock.patch.object(audit, "utc_today", return_value=audit.date(2026, 12, 2)):
            with self.assertRaisesRegex(ValueError, "overdue on the actual UTC evaluation day"):
                self.evaluate(approval)

    def test_future_approval_date_cannot_override_actual_utc_evaluation_day(self):
        approval = copy.deepcopy(self.approval)
        approval["approved_on"] = "2026-11-05"
        approval["evaluated_on"] = "2026-11-05"
        with self.assertRaisesRegex(ValueError, "later than the actual UTC evaluation day"):
            self.evaluate(approval)

    def test_bound_synthetic_approval_connects_release_and_keeps_local_output_unapproved(self):
        name = "touhou_little_maid"
        receipt = copy.deepcopy(self.audio_receipts[name])
        receipt["obligations"].append({"id": "synthetic-unsent-report", "action": "Synthetic unsent report",
            "clause": "Synthetic post-publication clause", "timing": "after_publication", "status": "pending",
            "due_within_days": 30, "published_on": None})
        self.write_audio_receipt(name, receipt)
        before = copy.deepcopy(self.report)
        result = self.evaluate()
        self.assertTrue(result["distributable"])
        self.assertTrue(result["release_acceptance_evaluated"])
        self.assertIsNone(result["release_approval"]["noninferiority_delta"])
        self.assertFalse(result["release_approval"]["statistical_equivalence_inferred"])
        self.assertEqual(self.report, before)
        self.assertNotIn(str(self.root), json.dumps(result["release_approval"]))
        artifact = self.root / "tlmnh-1.0.0.jar"
        artifact.write_bytes(self.data)
        with mock.patch.object(packaging, "PROJECT", self.root), \
                mock.patch.object(packaging, "source_binding", return_value=self.binding), \
                mock.patch.object(packaging.candidate_audit, "audit", return_value=self.report):
            released, approved, _ = packaging.packages(artifact, audit.digest(self.data),
                self.root / "snapshot.json", self.root, channel="release", release_approval=self.path)
            local, unchanged, _ = packaging.packages(artifact, audit.digest(self.data),
                self.root / "snapshot.json", self.root)
        self.assertTrue(approved["distributable"])
        self.assertFalse(unchanged["distributable"])
        for kind in ("client", "server"):
            with zipfile.ZipFile(io.BytesIO(released[kind][1])) as archive:
                names = archive.namelist()
                self.assertIn(".tlmnh-distribution/" + kind + "/release-decision.json", names)
                self.assertEqual(archive.read(".tlmnh-distribution/" + kind + "/licenses/TLMNH-LICENSE.txt"), self.gpl)
                for license_name in audit.LICENSES:
                    self.assertIn(".tlmnh-distribution/" + kind + "/licenses/" + license_name, names)
                manifest = json.loads(archive.read("manifest.json"))
                self.assertTrue(manifest["distributable"])
                packed_decision = json.loads(archive.read(".tlmnh-distribution/" + kind + "/release-decision.json"))
                pending = packed_decision["audio_publication"]["touhou_little_maid"]["obligations"][-1]
                self.assertEqual(pending["status"], "pending")
                self.assertIsNone(pending["published_on"])
                self.assertNotIn("evidence_sha256", pending)
                self.assertNotIn(str(self.root), json.dumps(packed_decision))
                self.assertTrue(manifest["audio_distribution_cleared"])
                self.assertFalse(manifest["audio_source_distribution_cleared"])
                packed_jar = archive.read(manifest["jar"]["path"])
                self.assertEqual(packed_jar, self.data)
                self.assertEqual(archive.read(".tlmnh-distribution/" + kind + "/provenance/" + audit.SOUND_MANIFEST),
                                 self.members[audit.SOUND_MANIFEST])
            with zipfile.ZipFile(io.BytesIO(local[kind][1])) as archive:
                self.assertFalse(json.loads(archive.read("manifest.json"))["distributable"])
                self.assertNotIn(".tlmnh-distribution/" + kind + "/release-decision.json", archive.namelist())
                self.assertEqual(archive.read(".tlmnh-distribution/" + kind + "/licenses/TLMNH-LICENSE.txt"), self.gpl)
                for license_name in audit.LICENSES:
                    self.assertIn(".tlmnh-distribution/" + kind + "/licenses/" + license_name, archive.namelist())


    def license_observation(self, members):
        data = self.archive(members)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            return audit.original_code_license(archive, archive.namelist(), audit.digest(data))

    def test_new_license_requires_full_gpl_root_metadata_and_notice(self):
        observed = self.license_observation(self.members)
        self.assertEqual(observed["spdx"], "GPL-3.0-only")
        self.assertEqual(observed["status"], "declared")
        for change in ("missing", "changed", "metadata", "missing_marker", "other_marker", "pending_marker"):
            with self.subTest(change=change):
                members = dict(self.members)
                if change == "missing":
                    del members[audit.ORIGINAL_LICENSE_MEMBER]
                elif change == "changed":
                    members[audit.ORIGINAL_LICENSE_MEMBER] += b"changed GPL text"
                elif change == "metadata":
                    document = json.loads(members["mcmod.info"])
                    document["modList"][0]["license"] = "GPL-3.0-or-later"
                    members["mcmod.info"] = json.dumps(document).encode()
                elif change == "missing_marker":
                    members["META-INF/THIRD_PARTY_NOTICES.md"] = b"Synthetic attribution only"
                elif change == "other_marker":
                    members["META-INF/THIRD_PARTY_NOTICES.md"] += b"TLMNH original code license: MIT\n"
                else:
                    members["META-INF/THIRD_PARTY_NOTICES.md"] += "原创代码的最终分发许可在正式版发布前单独确定".encode()
                with self.assertRaises(ValueError):
                    self.license_observation(members)
        # Matching corrupted source and JAR texts still fail the selected full-text fingerprint.
        corrupted = self.gpl + b"changed root and jar"
        (self.root / "LICENSE").write_bytes(corrupted)
        members = dict(self.members)
        members[audit.ORIGINAL_LICENSE_MEMBER] = corrupted
        with self.assertRaisesRegex(ValueError, "Root LICENSE"):
            self.license_observation(members)
        (self.root / "LICENSE").unlink()
        with self.assertRaises(FileNotFoundError):
            self.license_observation(self.members)

    def test_legacy_reading_is_exact_archive_and_snapshot_scoped(self):
        members = dict(self.members)
        del members[audit.ORIGINAL_LICENSE_MEMBER]
        members["mcmod.info"] = json.dumps({"modListVersion": 2, "modList": [{
            "modid": "tlmnh", "version": "0.1.0-indev.203"}]}).encode()
        members["META-INF/THIRD_PARTY_NOTICES.md"] = b"Synthetic historical undetermined notice"
        data = self.archive(members)
        legacy = {audit.digest(data): {"version": "0.1.0-indev.203",
                  "notice_sha256": audit.digest(members["META-INF/THIRD_PARTY_NOTICES.md"]),
                  "source_snapshot_sha256": self.binding["snapshot_sha256"], "overlay_allowed": True}}
        with mock.patch.object(audit, "LEGACY_UNLICENSED", legacy):
            observed = self.license_observation(members)
            self.assertEqual(observed["status"], "legacy_undetermined")
            self.assertIsNone(observed["spdx"])
            changed = dict(members)
            changed["extra.txt"] = b"same version but a different archive"
            with self.assertRaises(ValueError):
                self.license_observation(changed)
            binding = dict(self.binding, candidate_sha256=audit.digest(data))
            report = copy.deepcopy(self.report)
            report["sha256"] = audit.digest(data)
            report["checks"]["original_code_license"]["observed"] = observed
            report["checks"]["metadata"]["observed"]["version"] = "0.1.0-indev.203"
            report["original_source_license"] = "undetermined"
            report["release_blockers"].append({"code": "original_source_license_undetermined"})
            artifact = self.root / "tlmnh-0.1.0-indev.203.jar"
            artifact.write_bytes(data)
            with mock.patch.object(packaging, "PROJECT", self.root), \
                    mock.patch.object(packaging, "source_binding", return_value=binding), \
                    mock.patch.object(packaging.candidate_audit, "audit", return_value=report):
                local, _, _ = packaging.packages(artifact, audit.digest(data), self.root / "snapshot.json", self.root)
                with self.assertRaises(ValueError):
                    packaging.packages(artifact, audit.digest(data), self.root / "snapshot.json", self.root,
                                       channel="release", release_approval=self.path)
                binding["snapshot_sha256"] = "3" * 64
                with self.assertRaisesRegex(ValueError, "exact original source snapshot"):
                    packaging.packages(artifact, audit.digest(data), self.root / "snapshot.json", self.root)
            for kind in ("client", "server"):
                with zipfile.ZipFile(io.BytesIO(local[kind][1])) as archive:
                    self.assertNotIn(".tlmnh-distribution/" + kind + "/licenses/TLMNH-LICENSE.txt", archive.namelist())
                    self.assertFalse(json.loads(archive.read("manifest.json"))["distributable"])

    def test_static_local_audit_zip_also_retains_project_and_six_upstream_licenses(self):
        (self.root / "SOURCE_PROVENANCE.md").write_bytes(b"Synthetic source reference")
        result = audit.local_zip(self.data, self.report)
        with zipfile.ZipFile(self.root / result["path"]) as archive:
            self.assertEqual(archive.read("licenses/TLMNH-LICENSE.txt"), self.gpl)
            for name in audit.LICENSES:
                self.assertIn("licenses/" + name, archive.namelist())
            self.assertFalse(json.loads(archive.read("manifest.json"))["distributable"])


    def test_current_metadata_keeps_both_mixin_configs_and_client_only_ysmu(self):
        members = dict(self.members)
        members["mcmod.info"] = json.dumps({"modListVersion": 2, "modList": [{
            "modid": "tlmnh", "version": "1.0.0", "license": audit.PROJECT_LICENSE,
            "name": "Synthetic candidate", "mcversion": "1.7.10", "authorList": ["SYNTHETIC"],
            "requiredMods": ["battlegear2"]}]}).encode()
        manifest = ("Manifest-Version: 1.0\nTweakClass: org.spongepowered.asm.launch.MixinTweaker\n"
                    "FMLCorePluginContainsFMLMod: true\n"
                    "MixinConfigs: mixins.tlmnh.json,mixins.tlmnh.ysmu.json\n")
        members["META-INF/MANIFEST.MF"] = manifest.encode()
        members["mixins.tlmnh.json"] = json.dumps({"package": "com.tianluoqaq.tlmnh.mixin",
            "required": True, "compatibilityLevel": "JAVA_8", "refmap": "mixins.tlmnh.refmap.json"}).encode()
        optional = {"package": "com.tianluoqaq.tlmnh.compat.ysmu.client.mixin", "required": False,
            "compatibilityLevel": "JAVA_8", "refmap": "mixins.tlmnh.refmap.json", "target": "@env(DEFAULT)",
            "plugin": "com.tianluoqaq.tlmnh.core.MixinCompatibilityPlugin", "mixins": [], "server": [],
            "client": ["MixinMaidPassengerAlias"]}
        members["mixins.tlmnh.ysmu.json"] = json.dumps(optional).encode()
        members["mixins.tlmnh.refmap.json"] = b"{}"
        members["com/tianluoqaq/tlmnh/TLMNotHard.class"] = b"synthetic metadata-only member"
        members["com/tianluoqaq/tlmnh/core/MixinCompatibilityPlugin.class"] = b"synthetic metadata-only member"
        alias = "com/tianluoqaq/tlmnh/compat/ysmu/client/mixin/MixinMaidPassengerAlias.class"
        members[alias] = b"synthetic metadata-only member"

        def observe(selected):
            with zipfile.ZipFile(io.BytesIO(self.archive(selected))) as archive:
                return audit.metadata(archive)

        self.assertEqual(observe(members)["mixin_configs"], ["mixins.tlmnh.json", "mixins.tlmnh.ysmu.json"])
        for change in ("missing_config", "missing_implementation", "required", "common_side", "other_client", "undeclared_config"):
            with self.subTest(change=change):
                changed = dict(members)
                if change == "missing_config":
                    del changed["mixins.tlmnh.ysmu.json"]
                elif change == "missing_implementation":
                    del changed[alias]
                elif change == "undeclared_config":
                    changed["META-INF/MANIFEST.MF"] = manifest.replace(",mixins.tlmnh.ysmu.json", "").encode()
                else:
                    config = copy.deepcopy(optional)
                    if change == "required":
                        config["required"] = True
                    elif change == "common_side":
                        config["mixins"] = ["MixinMaidPassengerAlias"]
                    else:
                        config["client"] = ["DifferentMixin"]
                    changed["mixins.tlmnh.ysmu.json"] = json.dumps(config).encode()
                with self.assertRaises((ValueError, KeyError)):
                    observe(changed)

    def test_audit_only_historical_artifact_cannot_create_new_packages(self):
        members = dict(self.members)
        del members[audit.ORIGINAL_LICENSE_MEMBER]
        members["mcmod.info"] = json.dumps({"modListVersion": 2, "modList": [{
            "modid": "tlmnh", "version": "0.1.0-indev.206"}]}).encode()
        members["META-INF/THIRD_PARTY_NOTICES.md"] = b"Synthetic historical undetermined notice"
        data = self.archive(members)
        legacy = {audit.digest(data): {"version": "0.1.0-indev.206",
            "notice_sha256": audit.digest(members["META-INF/THIRD_PARTY_NOTICES.md"]),
            "source_snapshot_sha256": self.binding["snapshot_sha256"], "overlay_allowed": False}}
        with mock.patch.object(audit, "LEGACY_UNLICENSED", legacy):
            observed = self.license_observation(members)
            self.assertEqual(observed["status"], "legacy_undetermined")
            self.assertFalse(observed["legacy_overlay_allowed"])
            report = copy.deepcopy(self.report)
            report["checks"]["original_code_license"]["observed"] = observed
            report["sha256"] = audit.digest(data)
            artifact = self.root / "tlmnh-0.1.0-indev.206.jar"
            artifact.write_bytes(data)
            binding = dict(self.binding, candidate_sha256=audit.digest(data))
            with mock.patch.object(packaging, "PROJECT", self.root), \
                    mock.patch.object(packaging, "source_binding", return_value=binding), \
                    mock.patch.object(packaging.candidate_audit, "audit", return_value=report):
                for channel in ("local-validation", "release"):
                    with self.subTest(channel=channel), self.assertRaisesRegex(ValueError, "audit-only"):
                        packaging.packages(artifact, audit.digest(data), self.root / "snapshot.json", self.root,
                                           channel=channel, release_approval=self.path)
            with self.assertRaisesRegex(ValueError, "audit-only"):
                audit.local_zip(data, report)


if __name__ == "__main__":
    unittest.main()
