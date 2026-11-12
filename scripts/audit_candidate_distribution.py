"""Audit a candidate JAR; exit 0 means package integrity only, never release approval."""

import argparse
import copy
from datetime import date, datetime, timedelta, timezone
import math
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

sys.dont_write_bytecode = True

from verify_builtin_assets import verify as verify_assets
from verify_builtin_retention import verify as verify_retention
from verify_builtin_sounds import MANIFEST as SOUND_MANIFEST, MANIFEST_SHA256 as SOUND_MANIFEST_SHA256, verify as verify_sounds
from verify_bytecode import JAVA_8_MAJOR, inspect_archive

PROJECT = Path(__file__).resolve().parents[1]
RESOURCES = PROJECT / "src/main/resources"
UPSTREAM_COMMIT = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
PROJECT_LICENSE = "GPL-3.0-only"
ORIGINAL_LICENSE_MEMBER = "META-INF/TLMNH-LICENSE.txt"
# Exact full text selected for original adaptations; third-party licenses stay separate.
PROJECT_LICENSE_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
# Only these exact source-bound archives predate the packaged project-license declaration.
# A matching version string alone never enables historical reading or packaging.
LEGACY_UNLICENSED = {
    "24f1a0e9efbf8a0036fe1f48d404cf63ef1f82c770cdf3917d3dd289361d7c96": {
        "version": "0.1.0-indev.203",
        "notice_sha256": "4127b1c0c34e2efa0e1950a927b2a7d7220bfc8c65cea2ab67abb68315f422bd",
        "source_snapshot_sha256": "069ce9aa96c0d5a940b208fc0de1ff17fd9dfaf75d2ccfdf20de56cff163be44",
        "mixin_configs": ["mixins.tlmnh.json"],
        "overlay_allowed": True,
    },
    "ac9d818e28c8ef9c2720a5d5a8b5602f9aa3bd8aa80606995bf0e33aa3753dd1": {
        "version": "0.1.0-indev.206",
        "notice_sha256": "4127b1c0c34e2efa0e1950a927b2a7d7220bfc8c65cea2ab67abb68315f422bd",
        "source_snapshot_sha256": "1f05b3506abb0468a5c8e2b37b17e69ea935252bdf02285cf0399be7bc9cacb2",
        "mixin_configs": ["mixins.tlmnh.json", "mixins.tlmnh.ysmu.json"],
        "overlay_allowed": False,
    },
}
LICENSES = (
    "ExampleMod1.7.10-MIT.txt", "GeckoLib-MIT.txt", "JOML-MIT.txt",
    "TouhouLittleMaid-CC-BY-NC-SA-4.0.txt", "TouhouLittleMaid-MIT.txt", "UnnamedTeam-Molang-MIT.txt",
)
CLASS_ROOTS = (
    "com/tianluoqaq/tlmnh/", "com/github/tartaricacid/touhoulittlemaid/",
    "com/github/tartaricacid/simplebedrockmodel/",
)
SHADOW = "com/tianluoqaq/tlmnh/shadow/"
JOML = SHADOW + "org/joml/"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def utc_today():
    return datetime.now(timezone.utc).date()


def safe_member(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name and ":" not in name,
            f"Unsafe archive member: {name}")


def metadata(archive, artifact_sha256=None):
    raw = archive.read("mcmod.info")
    require(b"${" not in raw, "Unexpanded mod metadata")
    document = json.loads(raw)
    require(document.get("modListVersion") == 2 and len(document.get("modList", [])) == 1,
            "Expected one modListVersion 2 mod")
    mod = document["modList"][0]
    require(mod.get("modid") == "tlmnh" and mod.get("mcversion") == "1.7.10", "Unexpected mod or Minecraft version")
    version = mod.get("version", "")
    require(isinstance(version, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", version),
            "Missing or unsafe candidate version")
    require(mod.get("name") and isinstance(mod.get("authorList"), list) and bool(mod["authorList"])
            and isinstance(mod.get("requiredMods"), list) and "battlegear2" in mod["requiredMods"],
            "Incomplete name, attribution or required shield dependency")
    manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r", "")
    manifest = manifest.replace("\n ", "")
    fields = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    require(fields.get("Manifest-Version") == "1.0" and fields.get("TweakClass") == "org.spongepowered.asm.launch.MixinTweaker",
            "Missing runtime manifest entries")
    require(fields.get("FMLCorePluginContainsFMLMod") == "true", "Missing FML mod manifest marker")
    configs = fields.get("MixinConfigs", "").split(",")
    legacy = LEGACY_UNLICENSED.get(artifact_sha256)
    expected_configs = legacy["mixin_configs"] if legacy is not None else ["mixins.tlmnh.json", "mixins.tlmnh.ysmu.json"]
    require(configs == expected_configs, "Unexpected mixin configuration")
    mixins = json.loads(archive.read(configs[0]))
    require(mixins.get("package") == "com.tianluoqaq.tlmnh.mixin", "Unexpected mixin package")
    require(mixins.get("required") is True and mixins.get("compatibilityLevel") == "JAVA_8", "Unexpected mixin compatibility contract")
    require(mixins.get("refmap") in archive.namelist(), "Missing mixin refmap")
    if "mixins.tlmnh.ysmu.json" in configs:
        optional = json.loads(archive.read("mixins.tlmnh.ysmu.json"))
        plugin = "com.tianluoqaq.tlmnh.core.MixinCompatibilityPlugin"
        require(optional.get("package") == "com.tianluoqaq.tlmnh.compat.ysmu.client.mixin"
                and optional.get("required") is False and optional.get("compatibilityLevel") == "JAVA_8"
                and optional.get("target") == "@env(DEFAULT)" and optional.get("plugin") == plugin
                and optional.get("refmap") == mixins.get("refmap")
                and optional.get("mixins") == [] and optional.get("server") == []
                and optional.get("client") == ["MixinMaidPassengerAlias"], "Unexpected optional YSM client mixin contract")
        require(plugin.replace(".", "/") + ".class" in archive.namelist()
                and "com/tianluoqaq/tlmnh/compat/ysmu/client/mixin/MixinMaidPassengerAlias.class" in archive.namelist(),
                "Missing optional YSM client mixin or compatibility plugin implementation")
    require("com/tianluoqaq/tlmnh/TLMNotHard.class" in archive.namelist(), "Missing mod implementation")
    result = {"modid": mod["modid"], "version": version, "minecraft": mod["mcversion"], "required_mods": mod["requiredMods"], "mixin_configs": configs}
    if "license" in mod:
        result["license"] = mod["license"]
    return result


def class_boundary(names):
    classes = [name for name in names if name.endswith(".class")]
    foreign = [name for name in classes if not name.startswith(CLASS_ROOTS)
               or name.startswith(SHADOW) and not name.startswith(JOML)]
    require(not foreign, "Unexpected bundled implementation classes: " + ", ".join(foreign[:12]))
    require(not any(name.endswith(".jar") for name in names), "Nested dependencies are outside this candidate layout")
    require(not any(name.startswith("META-INF/versions/") or name.endswith("Kt.class") for name in classes),
            "Unexpected multi-release or Kotlin classes")
    joml = sum(name.startswith(JOML) for name in classes)
    require(joml > 0, "Missing relocated JOML implementation")
    return {"classes": len(classes), "relocated_joml_classes": joml,
            "scope": "package boundaries; not a proof of source ancestry or runtime compatibility"}


def provenance_and_licenses(archive, names, artifact_sha256=None):
    expected = {"META-INF/licenses/" + name for name in LICENSES}
    actual = {name for name in names if name.startswith("META-INF/licenses/") and not name.endswith("/")}
    require(actual == expected, "Candidate must contain the six recorded license files")
    for name in LICENSES:
        require(archive.read("META-INF/licenses/" + name) == (PROJECT / "licenses" / name).read_bytes(),
                "License differs from recorded text: " + name)
    notices = archive.read("META-INF/THIRD_PARTY_NOTICES.md")
    legacy = LEGACY_UNLICENSED.get(artifact_sha256)
    if legacy is None:
        require(notices == (PROJECT / "THIRD_PARTY_NOTICES.md").read_bytes(), "Third-party notices differ from the audit checkout")
    else:
        require(digest(notices) == legacy["notice_sha256"], "Historical attribution notice differs from its frozen record")
    source_manifests = {path.relative_to(RESOURCES).as_posix() for path in (RESOURCES / "assets/tlmnh").glob("*-provenance.json")}
    packed_manifests = {name for name in names if name.startswith("assets/tlmnh/") and name.endswith("-provenance.json")}
    require(source_manifests == packed_manifests, "Provenance manifest inventory differs from the audit checkout")
    for name in sorted(source_manifests | {"assets/tlmnh/builtin-models.json", "assets/tlmnh/builtin-sounds.json"}):
        data = archive.read(name)
        require(data == (RESOURCES / name).read_bytes(), "Source summary differs: " + name)
        summary = json.loads(data)
        require(summary.get("commit", summary.get("upstream_commit")) == UPSTREAM_COMMIT,
                "Source summary is not anchored to the fixed upstream: " + name)
    return {"licenses": list(LICENSES), "provenance_manifests": sorted(source_manifests),
            "third_party_notices_sha256": digest(notices),
            "anchor": "frozen historical notice; other source manifests match the audit checkout" if legacy is not None
                      else "current audit checkout; no source/JAR identity assertion"}


def original_code_license(archive, names, artifact_sha256):
    document = json.loads(archive.read("mcmod.info"))
    require(document.get("modListVersion") == 2 and len(document.get("modList", [])) == 1,
            "Expected one mod for original-code license declaration")
    mod = document["modList"][0]
    require(mod.get("modid") == "tlmnh", "Project license belongs to another mod")
    notice = archive.read("META-INF/THIRD_PARTY_NOTICES.md")
    legacy = LEGACY_UNLICENSED.get(artifact_sha256)
    if legacy is not None:
        require(mod.get("version") == legacy["version"] and "license" not in mod
                and ORIGINAL_LICENSE_MEMBER not in names and digest(notice) == legacy["notice_sha256"],
                "Historical unlicensed archive differs from its frozen identity")
        return {"spdx": None, "status": "legacy_undetermined", "member": None,
                "legacy_artifact_sha256": artifact_sha256,
                "legacy_source_snapshot_sha256": legacy["source_snapshot_sha256"],
                "legacy_overlay_allowed": legacy.get("overlay_allowed") is True,
                "scope": "Exact recorded historical artifact only; no project license was packaged and formal release remains blocked"}
    require(mod.get("license") == PROJECT_LICENSE, "Original-code metadata must declare GPL-3.0-only")
    require(ORIGINAL_LICENSE_MEMBER in names, "Missing packaged project GPL full text")
    raw = archive.read(ORIGINAL_LICENSE_MEMBER)
    root_license = (PROJECT / "LICENSE").read_bytes()
    require(digest(root_license) == PROJECT_LICENSE_SHA256, "Root LICENSE differs from the selected full GPL text")
    require(raw == root_license, "Packaged project GPL full text differs from root LICENSE")
    lines = notice.decode("utf-8").splitlines()
    markers = [line for line in lines if line.startswith("TLMNH original code license:")]
    require(markers == ["TLMNH original code license: " + PROJECT_LICENSE]
            and "原创代码的最终分发许可在正式版发布前单独确定" not in notice.decode("utf-8"),
            "Project notice must declare exactly GPL-3.0-only without an undetermined marker")
    return {"spdx": PROJECT_LICENSE, "status": "declared", "member": ORIGINAL_LICENSE_MEMBER,
            "sha256": digest(raw), "root_license_sha256": digest(root_license),
            "scope": "Declared original adaptation-code license only; retained upstream code/assets/audio keep their own terms"}


def sounds(archive, names):
    return verify_sounds(archive.read, names)


def audit(artifact, data):
    report = {"schema": "candidate-distribution-audit-v1", "scope": "local-validation-only", "distributable": False,
              "artifact": artifact.name, "sha256": digest(data), "bytes": len(data), "checks": {},
              "release_blockers": [{"code": "audio_publication_not_evaluated", "evidence": "independent candidate-bound audio publication decisions"}]}

    def check(name, operation):
        try:
            report["checks"][name] = {"passed": True, "observed": operation()}
        except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError, RuntimeError, zipfile.BadZipFile) as error:
            report["checks"][name] = {"passed": False, "error": str(error)}

    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()

        def layout():
            require(len(names) == len(set(names)), "Duplicate ZIP member names")
            for name in names:
                safe_member(name)
            return {"entries": len(names)}

        def bytecode():
            entries = inspect_archive(data, artifact.name)
            invalid = [entry for entry in entries if entry["major"] > JAVA_8_MAJOR or entry["minor"] == 65535]
            require(bool(entries) and not invalid, "Empty or incompatible Java bytecode: " + str(invalid[:8]))
            return {"classes": len(entries), "major_versions": sorted({entry["major"] for entry in entries}),
                    "maximum_allowed_major": JAVA_8_MAJOR, "scope": "class headers only; no VM loading or runtime acceptance"}

        check("archive_layout", layout)
        check("metadata", lambda: metadata(archive, report["sha256"]))
        check("java8_bytecode", bytecode)
        check("third_party_class_boundary", lambda: class_boundary(names))
        check("licenses_and_source_anchors", lambda: provenance_and_licenses(archive, names, report["sha256"]))
        check("original_code_license", lambda: original_code_license(archive, names, report["sha256"]))
        check("builtin_assets", lambda: verify_assets(archive.read))
        check("retained_builtin_resources", lambda: verify_retention(archive.read, names))
        check("sound_sources_and_events", lambda: sounds(archive, names))
        original = report["checks"]["original_code_license"]
        if original["passed"] and original["observed"]["status"] == "declared":
            report["original_source_license"] = original["observed"]["spdx"]
        else:
            report["original_source_license"] = "undetermined" if original["passed"] else "not_certified_by_this_audit"
            report["release_blockers"].append({"code": "original_source_license_" + report["original_source_license"],
                                               "evidence": "META-INF/THIRD_PARTY_NOTICES.md"})
    meta = report["checks"]["metadata"]
    if meta["passed"] and "indev" in meta["observed"]["version"].lower():
        report["release_blockers"].append({"code": "indev_candidate", "evidence": "mcmod.info"})
    report["integrity_passed"] = all(check["passed"] for check in report["checks"].values())
    if not report["integrity_passed"]:
        report["release_blockers"].append({"code": "candidate_integrity_failed", "checks": [name for name, check in report["checks"].items() if not check["passed"]]})
    report["release_acceptance_evaluated"] = False
    return report


RELEASE_ACCEPTANCE = ("core_gameplay", "benchmark", "models_equipment", "multiplayer_persistence", "user_play")
AUDIO_PACKS = {"littlemaid_peco", "touhou_little_maid"}


def approve_release(report, data, approval_path, binding, package_documents):
    """Evaluate an explicit project decision; never infer permission or gameplay acceptance from integrity."""
    require(approval_path is not None, "Formal release requires an explicit approval input")
    require(report["integrity_passed"] is True, "Release cannot waive failed candidate integrity")
    path = Path(approval_path).resolve(strict=True)
    require(path.is_file() and 0 < path.stat().st_size <= 1024 * 1024, "Bounded regular release approval required")

    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, "Duplicate release approval key: " + key)
            value[key] = item
        return value

    raw = path.read_bytes()
    approval = json.loads(raw, object_pairs_hook=unique)
    require(approval.get("schema") == "tlmnh-release-approval-v2" and approval.get("decision") == "approved",
            "Release approval is absent, pending or unrecognized")
    reviewer = approval.get("approved_by")
    require(isinstance(reviewer, str) and 0 < len(reviewer.strip()) <= 200, "Explicit approval reviewer required")
    require(isinstance(approval.get("approved_on"), str), "Explicit approval date required")
    approved_on = date.fromisoformat(approval["approved_on"])
    evaluated_on = utc_today()
    require(approved_on <= evaluated_on, "Release approval date is later than the actual UTC evaluation day")
    require(isinstance(package_documents, dict) and bool(package_documents)
            and approval["package_documents_sha256"] == package_documents, "Approved package documents/updater differ")
    candidate = approval["candidate"]
    version = report["checks"]["metadata"]["observed"]["version"]
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:\+[A-Za-z0-9.-]+)?", version),
            "Only an explicitly selected stable version can be formally released")
    require(candidate == {"version": version, "sha256": digest(data),
                          "source_snapshot_sha256": binding["snapshot_sha256"]}
            and report["sha256"] == binding["candidate_sha256"] == digest(data),
            "Approval candidate/version/source snapshot differs")

    def text(value, label):
        require(isinstance(value, str) and 0 < len(value.strip()) <= 10000, label + " must be explicit")
        return value

    def read_evidence(value, relative_to=path):
        require(isinstance(value, dict) and set(value) == {"path", "sha256"}, "Exact evidence file/hash required")
        named = Path(text(value["path"], "Evidence path"))
        target = (relative_to.parent / named).resolve(strict=True)
        require(target not in (path, relative_to) and target.is_file()
                and 0 < target.stat().st_size <= 32 * 1024 * 1024,
                "Nonempty independent acceptance evidence required")
        expected = value["sha256"]
        raw_evidence = target.read_bytes()
        require(isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected)
                and digest(raw_evidence) == expected, "Acceptance evidence missing or changed")
        return expected, raw_evidence, target

    def evidence(value):
        return read_evidence(value)[0]

    def audio_publication(name, decision, source_sha256):
        require(decision["decision"] == "approved", "Audio publication decision is pending: " + name)
        text(decision["basis"], "Audio permission basis")
        steps = decision["publication_steps"]
        require(isinstance(steps, list) and bool(steps), "Concrete audio attribution/report steps required")
        for step in steps:
            text(step, "Audio publication step")
        receipt_sha, receipt_raw, receipt_path = read_evidence(decision["evidence"])
        receipt = json.loads(receipt_raw, object_pairs_hook=unique)
        require(receipt.get("schema") == "tlmnh-audio-publication-evidence-v1"
                and receipt.get("decision") == "approved" and receipt.get("pack") == name,
                "Structured approved audio publication evidence required: " + name)
        require(receipt["candidate"] == candidate and receipt["provenance_sha256"] == source_sha256,
                "Audio evidence candidate or immutable provenance differs: " + name)
        terms = receipt["terms"]
        text(terms["url"], "Audio terms URL")
        text(terms["revision"], "Audio terms revision")
        text(terms["language"], "Audio terms language")
        terms_sha = read_evidence(terms["document"], receipt_path)[0]
        applicability = terms["applicability"]
        require(applicability["decision"] == "applicable", "Audio terms applicability is pending")
        text(applicability["assessment"], "Audio terms applicability assessment")
        applicability_sha = read_evidence(applicability["evidence"], receipt_path)[0]
        terms_url = terms["url"].split("?", 1)[0].split("#", 1)[0].rstrip("/")
        if name == "touhou_little_maid" and (terms["language"] == "ja"
                or terms_url == "https://amitaro.net/voice/voice_rule"):
            require(terms["language"] == "ja" and applicability.get("japanese_comprehension") == "confirmed",
                    "Amitaro Japanese terms require confirmed Japanese comprehension")
        require(terms["extractable_archives"] == "permitted",
                "Audio terms must cover the actual extractable JAR distribution")
        proof_hashes = {"terms": terms_sha, "applicability": applicability_sha}
        for field, state in (("authorization", "authorized"), ("attribution", "completed")):
            proof = receipt[field]
            require(proof["decision"] == state, "Audio " + field + " is pending")
            proof_hashes[field] = read_evidence(proof["evidence"], receipt_path)[0]
        obligations = receipt["obligations"]
        require(isinstance(obligations, list), "Explicit audio obligations list required")
        seen, portable_steps = set(), []
        for obligation in obligations:
            identifier = text(obligation["id"], "Audio obligation ID")
            require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", identifier), "Portable audio obligation ID required")
            require(identifier not in seen, "Duplicate audio obligation")
            seen.add(identifier)
            text(obligation["action"], "Audio obligation action")
            text(obligation["clause"], "Audio obligation terms clause")
            timing, status = obligation["timing"], obligation["status"]
            require(timing in ("before_release", "after_publication") and status in ("completed", "pending"),
                    "Unknown audio obligation timing or status")
            step = {"id": identifier, "timing": timing, "status": status}
            if status == "completed":
                completed_on = date.fromisoformat(obligation["completed_on"])
                require(completed_on <= approved_on, "Audio obligation completion is in the future")
                step.update(completed_on=completed_on.isoformat(),
                            evidence_sha256=read_evidence(obligation["evidence"], receipt_path)[0])
            else:
                require(timing == "after_publication", "Pre-release audio obligation is incomplete")
                days = obligation["due_within_days"]
                require(type(days) is int and 0 < days <= date.max.toordinal() - approved_on.toordinal(),
                        "Explicit valid post-publication deadline required")
                published = obligation["published_on"]
                if published is not None:
                    published_on = date.fromisoformat(published)
                    require(published_on <= approved_on <= published_on + timedelta(days=days),
                            "Pending post-publication audio obligation is overdue or publication is in the future")
                    require(evaluated_on <= published_on + timedelta(days=days),
                            "Pending post-publication audio obligation is overdue on the actual UTC evaluation day")
                require("evidence" not in obligation and "completed_on" not in obligation,
                        "Pending audio obligation must retain its unsent status")
                step.update(due_within_days=days, published_on=published)
            portable_steps.append(step)
        return {"evidence_sha256": receipt_sha, "decision": "approved", "proof_sha256": proof_hashes,
                "obligations": portable_steps}

    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate candidate member cannot be approved")
        declared = original_code_license(archive, names, digest(data))
        require(declared["status"] == "declared" and declared["spdx"] == PROJECT_LICENSE,
                "Historical undetermined license cannot be approved as a new release")
        attribution = approval["attribution"]
        notice = archive.read("META-INF/THIRD_PARTY_NOTICES.md")
        require(attribution["notice_sha256"] == digest(notice), "Approved attribution notice differs")
        provenance = report["checks"]["licenses_and_source_anchors"]["observed"]["provenance_manifests"]
        expected_sources = {name: digest(archive.read(name)) for name in provenance}
        require(attribution["provenance_sha256"] == expected_sources, "Approved resource source inventory differs")
        original = attribution["original_code_license"]
        require(isinstance(original, dict) and set(original) == {"spdx", "member", "sha256"},
                "Exact packaged original-code license identity required")
        spdx = text(original["spdx"], "Selected original-code license")
        require(spdx == PROJECT_LICENSE == declared["spdx"], "Approved original-code license differs from the declared GPL-3.0-only")
        require(original["member"] == ORIGINAL_LICENSE_MEMBER
                and original["sha256"] == digest(archive.read(ORIGINAL_LICENSE_MEMBER)),
                "Selected original-code license text is missing or changed")
        require(bool(archive.read(ORIGINAL_LICENSE_MEMBER).decode("utf-8").strip()),
                "Original-code license text is empty")
        require(("TLMNH original code license: " + spdx) in notice.decode("utf-8").splitlines()
                and "原创代码的最终分发许可在正式版发布前单独确定" not in notice.decode("utf-8"),
                "Packaged original-code license is still undetermined or does not match the decision")
        source_sha256 = digest(archive.read(SOUND_MANIFEST))
        require(source_sha256 == SOUND_MANIFEST_SHA256, "Immutable audio source manifest differs")
        packs = approval["audio_packs"]
        require(isinstance(packs, dict) and set(packs) == AUDIO_PACKS, "Both complete retained audio packs require explicit decisions")
        audio_decisions = {name: audio_publication(name, decision, source_sha256)
                           for name, decision in packs.items()}
        audio_evidence = {name: decision["evidence_sha256"] for name, decision in audio_decisions.items()}

    accepted = approval["acceptance"]
    require(isinstance(accepted, dict) and set(accepted) == set(RELEASE_ACCEPTANCE), "All existing release scopes need explicit acceptance")
    accepted_hashes = {}
    for name in RELEASE_ACCEPTANCE:
        decision = accepted[name]
        require(decision["decision"] == "accepted", "Release acceptance pending: " + name)
        text(decision["assessment"], "Actual acceptance assessment")
        limitations = decision["known_limitations"]
        require(isinstance(limitations, list) and all(isinstance(item, str) and item.strip() for item in limitations),
                "Actual known limitations must be an explicit list")
        records = decision["evidence"]
        require(isinstance(records, list) and bool(records), "Nonempty bound acceptance evidence required: " + name)
        accepted_hashes[name] = [evidence(item) for item in records]
    benchmark = accepted["benchmark"]
    require(benchmark["covered_classes"] == ["B%02d" % n for n in range(1, 15)], "Benchmark review must address the existing14 classes")
    require("noninferiority_delta" in benchmark, "Benchmark uncertainty/margin must be explicit")
    delta = benchmark["noninferiority_delta"]
    require(delta is None or type(delta) in (float, int) and math.isfinite(delta) and delta >= 0,
            "Unknown noninferiority margin cannot be invented")
    if delta is not None:
        evidence(benchmark["delta_approval_evidence"])
    # A null margin is allowed. The explicit benchmark assessment remains a project decision,
    # never an automatic claim of equivalence or a request to accept positive regression.
    ordinary_blockers = {"audio_publication_not_evaluated", "audio_distribution_not_cleared", "original_source_license_undetermined",
                         "original_source_license_not_certified_by_this_audit"}
    require(all(item["code"] in ordinary_blockers for item in report["release_blockers"]),
            "An unrelated release blocker cannot be waived by this approval")
    portable = {"schema": "tlmnh-approved-release-decision-v2", "approval_sha256": digest(raw),
                "evaluated_on": evaluated_on.isoformat(), "candidate": candidate, "package_documents_sha256": package_documents,
                "notice_sha256": attribution["notice_sha256"],
                "provenance_sha256": attribution["provenance_sha256"], "original_code_license": original,
                "audio_evidence_sha256": audio_evidence, "audio_publication": audio_decisions, "acceptance_evidence_sha256": accepted_hashes,
                "benchmark_classes_reviewed": benchmark["covered_classes"], "noninferiority_delta": delta,
                "statistical_equivalence_inferred": False,
                "scope": "Explicit artifact-bound project approval; not a machine legal opinion or a new statistical verdict"}
    result = copy.deepcopy(report)
    result.update(scope="approved-release", distributable=True, release_acceptance_evaluated=True,
                  release_blockers=[], original_source_license=spdx, release_approval=portable)
    return result


def local_zip(artifact_data, report):
    require(report["integrity_passed"], "A local validation ZIP requires passing package integrity checks")
    original = report["checks"]["original_code_license"]["observed"]
    require(original["status"] != "legacy_undetermined" or original.get("legacy_overlay_allowed") is True,
            "This historical artifact is audit-only; a new package requires the declared project GPL license")
    dist = PROJECT / "dist"
    require(not dist.exists() or dist.resolve() == dist, "dist must be the checkout's own directory, not a redirected path")
    version = report["checks"]["metadata"]["observed"]["version"]
    jar_name = "tlmnh-" + version + ".jar"
    contents = {"client/mods/" + jar_name: artifact_data, "server/mods/" + jar_name: artifact_data}
    with zipfile.ZipFile(io.BytesIO(artifact_data)) as archive:
        for name in LICENSES:
            contents["licenses/" + name] = archive.read("META-INF/licenses/" + name)
        contents["THIRD_PARTY_NOTICES.md"] = archive.read("META-INF/THIRD_PARTY_NOTICES.md")
        if ORIGINAL_LICENSE_MEMBER in archive.namelist():
            contents["licenses/TLMNH-LICENSE.txt"] = archive.read(ORIGINAL_LICENSE_MEMBER)
        for name in report["checks"]["licenses_and_source_anchors"]["observed"]["provenance_manifests"]:
            contents["provenance/" + name] = archive.read(name)
        for name in ("assets/tlmnh/builtin-models.json", "assets/tlmnh/builtin-sounds.json"):
            contents["provenance/" + name] = archive.read(name)
    source = (PROJECT / "SOURCE_PROVENANCE.md").read_bytes()
    contents["source-reference/SOURCE_PROVENANCE.md"] = source
    manifest = {"scope": "local-validation-only", "distributable": False, "audio_distribution_cleared": False,
                "jar_sha256": report["sha256"], "client_server_jar_identical": True,
                "release_blockers": report["release_blockers"], "source_reference_sha256": digest(source),
                "source_reference_scope": "audit checkout reference; not proof that this source produced the JAR"}
    contents["manifest.json"] = encoded(manifest)
    contents["audit-report.json"] = encoded(report)
    contents["LOCAL-VALIDATION-ONLY.txt"] = b"LOCAL VALIDATION ONLY\nNot a formal release or distribution clearance.\nReview manifest.json and audit-report.json release blockers before any publication.\n"
    contents["SHA256SUMS.txt"] = "".join(digest(value) + "  " + name + "\n" for name, value in sorted(contents.items())).encode("utf-8")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in sorted(contents.items()):
            archive.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), value, compress_type=zipfile.ZIP_DEFLATED)
    result = buffer.getvalue()
    dist.mkdir(exist_ok=True)
    target = dist / f"tlmnh-{version}-local-validation-{report['sha256'][:12]}.zip"
    with target.open("xb") as stream:
        stream.write(result)
    return {"path": target.relative_to(PROJECT).as_posix(), "sha256": digest(result), "bytes": len(result)}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--report", type=Path, help="Explicitly write the JSON audit report")
    parser.add_argument("--local-zip", action="store_true", help="Create a local-validation-only ZIP under ignored dist; never overwrite")
    args = parser.parse_args()
    try:
        artifact = args.artifact.resolve(strict=True)
        data = artifact.read_bytes()
        report = audit(artifact, data)
        if args.local_zip:
            report["local_zip"] = local_zip(data, report)
        if args.report:
            require(args.report.resolve() != artifact, "Report must not replace the audited JAR")
            args.report.parent.mkdir(parents=True, exist_ok=True)
            with args.report.open("xb") as stream:
                stream.write(encoded(report))
        print(encoded(report).decode("utf-8"), end="")
        return 0 if report["integrity_passed"] else 1
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError, RuntimeError, zipfile.BadZipFile) as error:
        print(json.dumps({"scope": "local-validation-only", "distributable": False, "integrity_passed": False,
                          "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
