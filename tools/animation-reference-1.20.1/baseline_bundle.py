"""导出和校验有界原版动画证据包；导出不自动批准新基线"""

import argparse
import gzip
import hashlib
import io
import json
import math
import re
import shutil
import tempfile
from pathlib import Path, PurePosixPath

import compare_aa
from compare_ab import BASE, pinned_assets, upstream_asset
from reference_environment import PROBE
from scenario_contract import IDLE_MODE, WALK_MODE, HEAD_MODE

SCHEMA = "animation-baseline-bundle-v1"
SCENARIOS = ("idle", "idle_walk_idle", "head_turn")
MODELS = ("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")
MODES = dict(zip(SCENARIOS, (IDLE_MODE, WALK_MODE, HEAD_MODE)))
MAX_FILE = 16 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{64}\Z")
PRIVATE_PATH = re.compile(r"(?i)(?:(?<![a-z0-9])[a-z]:[\\/]|\\\\|/(?:users|home|volumes)/)")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result

    def invalid(value):
        raise ValueError("Non-finite JSON value: " + value)

    def number(value):
        result = float(value)
        if not math.isfinite(result):
            invalid(value)
        return result

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid, parse_float=number)


def public_strings(value):
    if isinstance(value, dict):
        for key, child in value.items():
            public_strings(key)
            public_strings(child)
    elif isinstance(value, list):
        for child in value:
            public_strings(child)
    elif isinstance(value, str) and PRIVATE_PATH.search(value):
        raise ValueError("Evidence contains an absolute local path; value omitted")


def relative_file(root, name):
    no_links(root)
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("Invalid bundle path")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("Bundle path must be a normalized relative path")
    target = root.joinpath(*path.parts)
    for part in (target, *target.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Bundle paths must not cross links or junctions")
        if part == root:
            break
    target.resolve().relative_to(root.resolve())
    return target


def no_links(path):
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Evidence roots must not cross links or junctions")


def read_bounded(path, maximum=MAX_FILE):
    if not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("Missing or oversized evidence file: " + path.name)
    with path.open("rb") as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError("Evidence file exceeds its bound")
    return raw


def unpack_sample(raw):
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
        expanded = stream.read(MAX_FILE + 1)
    if len(expanded) > MAX_FILE:
        raise ValueError("Expanded capture exceeds its bound")
    return expanded


def reference_identity(sample, build_raw):
    build = strict_json(build_raw)
    if build.get("schema") != "reference-build-identity-v1" or build.get("status") != "LOCK_VERIFIED":
        raise ValueError("A verified reference build identity is required; inventory is not approval")
    identity = sample.get("runtimeIdentity", {})
    ref = sample.get("buildIdentity", {})
    if (ref.get("schema") != "reference-build-identity-ref-v1" or ref.get("sha256") != digest(build_raw)
            or identity.get("schema") != "reference-runtime-identity-v1" or identity.get("status") != "VERIFIED"
            or identity.get("buildIdentitySha256") != digest(build_raw) or identity.get("upstreamCommit") != BASE):
        raise ValueError("Capture is not bound to a verified reference build and runtime identity")
    classes = strict_json((PROBE / "identity-classes.json").read_bytes())["classes"]
    if (build.get("upstreamCommit") != BASE
            or build.get("identityClassesSha256") != digest((PROBE / "identity-classes.json").read_bytes())
            or set(build.get("compiledClasses", {})) != set(classes)
            or set(identity.get("classes", {})) != set(classes)):
        raise ValueError("Missing or unexpected observed reference classes")
    for name, value in identity["classes"].items():
        if not SHA.fullmatch(value.get("definitionSha256", "")) or not SHA.fullmatch(value.get("resourceSha256", "")):
            raise ValueError("Missing actual reference definition/resource digest")
        if value["resourceSha256"] != build["compiledClasses"][name]:
            raise ValueError("Runtime reference resource differs from its recorded compiled output")
        if (type(value.get("definitionCount")) is not int or value["definitionCount"] != 1
                or value.get("definitionStage") != "premain-transformer-input-after-defining-loader-transformations"):
            raise ValueError("Reference class was not observed at its first definition")
    for key in ("patchSha256", "probeSourcesSha256"):
        if not SHA.fullmatch(identity.get(key, "")) or identity[key] != build.get(key):
            raise ValueError("Missing or inconsistent runtime source identity: " + key)
    if (identity.get("java") != build.get("java") or not isinstance(identity.get("java"), dict)
            or not SHA.fullmatch(identity.get("agentSha256", ""))
            or identity["agentSha256"] != build.get("identityAgent", {}).get("sha256")
            or identity.get("identityClassesSha256") != build.get("identityClassesSha256")):
        raise ValueError("Runtime Java or observing agent differs from the verified build identity")
    public_strings(sample)
    public_strings(build)
    return identity


def verify_build_lock(build_raw, lock_raw):
    build, lock = strict_json(build_raw), strict_json(lock_raw)
    if (build.get("schema") != "reference-build-identity-v1" or build.get("status") != "LOCK_VERIFIED"
            or lock.get("schema") != "reference-toolchain-lock-v1" or lock.get("status") != "APPROVED"
            or build.get("toolchainLockSha256") != digest(lock_raw)):
        raise ValueError("Reference build does not match its approved toolchain lock")
    for key in ("gradleVersion", "java", "buildscriptArtifacts", "plugins"):
        if build.get(key) != lock.get(key):
            raise ValueError("Reference toolchain lock mismatch: " + key)
    if (build.get("gradleVersion") != "8.14.3" or not build.get("java", {}).get("runtimeVersion", "").startswith("17.")
            or not build.get("buildscriptArtifacts") or len(build.get("plugins", [])) != 4):
        raise ValueError("Unexpected approved reference toolchain")
    public_strings(build)
    public_strings(lock)


def sample_contract(sample, scenario, model):
    compare_aa.validate(sample)
    if (sample.get("inputMode") != MODES[scenario] or len(sample["frames"]) != (30 if scenario == "idle" else 72)
            or type(sample.get("seed")) is not int or sample["seed"] != 424242
            or sample.get("faultInjection") != "none" or sample.get("upstreamCommit") != BASE):
        raise ValueError("Capture does not match the approved bounded scenario")
    if any(frame["modelChoice"] != model for frame in sample["frames"]):
        raise ValueError("Unexpected original model")


def producer_tools(build_raw):
    import subprocess
    from reference_environment import GIT, PROJECT
    files = {path.relative_to(PROBE).as_posix(): digest(path.read_bytes()) for path in PROBE.rglob("*")
             if path.is_file() and path.suffix in (".py", ".java", ".gradle", ".json", ".patch")
             and "baselines" not in path.relative_to(PROBE).parts and "__pycache__" not in path.parts}
    build = strict_json(build_raw)
    if build.get("referenceInitSha256") != files["reference.init.gradle"] or build.get("patchSha256") != files["upstream-probe.patch"]:
        raise ValueError("Reference build tools changed after compilation")
    if any(files.get(entry["file"]) != entry["sha256"] for entry in build["probeSources"]):
        raise ValueError("Reference probe sources changed after compilation")
    head = subprocess.check_output([GIT, "-C", str(PROJECT), "rev-parse", "HEAD"], text=True).strip()
    return {"gitCommit": head, "files": files, "scope": "producer sources at export; consumer sources recorded separately per run"}


def export_bundle(destination, baseline_id, aa_paths, runtime, upstream, build_identity, toolchain_lock):
    no_links(destination)
    no_links(runtime)
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,79}", baseline_id):
        raise ValueError("Invalid baseline id")
    if destination.exists():
        raise ValueError("Baseline destination already exists; existing evidence is immutable")
    build_raw = read_bounded(build_identity)
    lock_raw = read_bounded(toolchain_lock)
    verify_build_lock(build_raw, lock_raw)
    reports = {}
    for path in aa_paths:
        raw = read_bounded(path)
        report = strict_json(raw)
        scenario = report.get("scenario")
        if scenario not in SCENARIOS or scenario in reports or report.get("passed") is not True:
            raise ValueError("Exactly one passing A/A report per approved scenario is required")
        reports[scenario] = (report, digest(raw))
    if set(reports) != set(SCENARIOS):
        raise ValueError("Missing approved A/A scenario")
    files, assets, cases = {}, {}, []
    raw_total = 0
    identity = None

    def include(name, raw, kind, **extra):
        if name in files:
            if files[name][0] != raw:
                raise ValueError("Conflicting duplicate bundle file")
            return
        if len(raw) > MAX_FILE:
            raise ValueError("Bundle member too large")
        files[name] = (raw, {"path": name, "kind": kind, "sha256": digest(raw), "bytes": len(raw), **extra})

    include("reference-build-identity.json", build_raw, "build_identity")
    include("reference-toolchain-lock.json", lock_raw, "toolchain_lock")
    for scenario in SCENARIOS:
        aa, aa_sha = reports[scenario]
        by_model = {case["model"]: case for case in aa["models"]}
        if len(aa["models"]) != 2 or set(by_model) != set(MODELS):
            raise ValueError("Unexpected A/A model matrix")
        for model_index, model in enumerate(MODELS):
            case = by_model[model]
            if case.get("passed") is not True or len(case.get("samples", [])) != 3:
                raise ValueError("Three independent original samples are required")
            if (len({entry["file"] for entry in case["samples"]}) != 3
                    or len({entry["sha256"] for entry in case["samples"]}) != 3):
                raise ValueError("A/A requires three distinct original captures")
            samples, values = [], []
            for index, entry in enumerate(case["samples"]):
                source = relative_file(runtime, entry["file"])
                raw = read_bounded(source)
                raw_total += len(raw)
                if raw_total > MAX_TOTAL:
                    raise ValueError("Uncompressed captures exceed total size bound")
                if digest(raw) != entry["sha256"]:
                    raise ValueError("Original sample changed after A/A")
                sample = strict_json(raw)
                sample_contract(sample, scenario, model)
                actual_identity = reference_identity(sample, build_raw)
                if identity is None:
                    identity = actual_identity
                if actual_identity != identity:
                    raise ValueError("Reference runtime identity changed between samples")
                for location in sample["actualAssetSha256"]:
                    if location not in assets:
                        assets[location] = upstream_asset(location, upstream)
                pinned_assets(sample["actualAssetSha256"],
                              {name: digest(assets[name]) for name in sample["actualAssetSha256"]}, assets)
                name = f"captures/{scenario}-{model_index}-{index}.json.gz"
                include(name, gzip.compress(raw, mtime=0), "capture", rawSha256=digest(raw), rawBytes=len(raw))
                samples.append({"file": name, "sha256": digest(raw)})
                values.append(sample)
            comparisons = [compare_aa.compare(values[0], value) for value in values[1:]]
            if any(value["status"] != "PASS_REPEATABILITY" for value in comparisons):
                raise ValueError("Original A/A did not reproduce during export")
            if not any(frame["bones"] != values[0]["frames"][0]["bones"] for frame in values[0]["frames"][1:]):
                raise ValueError("Original animation was completely frozen")
            cases.append({"scenario": scenario, "model": model, "samples": samples,
                          "sourceAaSha256": aa_sha, "comparisons": comparisons})
    asset_entries = {}
    for location, raw in sorted(assets.items()):
        name = "assets/" + digest(raw) + ".bin"
        include(name, raw, "canonical_asset")
        asset_entries[location] = name
    manifest = {"schema": SCHEMA, "id": baseline_id, "upstreamCommit": BASE,
                "scope": "two reviewed controlled models and three CPU input contracts; not GPU or gameplay acceptance",
                "referenceBuildIdentity": "reference-build-identity.json", "cases": cases, "assets": asset_entries,
                "referenceToolchainLock": "reference-toolchain-lock.json",
                "files": [entry for _, entry in files.values()],
                "producerTools": producer_tools(build_raw),
                "assetLicense": "See THIRD_PARTY_NOTICES.md and unchanged model metadata credits",
                "runtimeIdentity": identity, "approvedForBulk": False, "renderingVerified": False}
    public_strings(manifest)
    manifest_raw = json_bytes(manifest)
    if sum(len(raw) for raw, _ in files.values()) > MAX_TOTAL:
        raise ValueError("Bundle exceeds total size bound")
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".baseline-staging-", dir=destination.parent))
    try:
        for name, (raw, _) in files.items():
            target = relative_file(stage, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        (stage / "manifest.json").write_bytes(manifest_raw)
        verify_bundle(stage, digest(manifest_raw))
        if destination.exists():
            raise ValueError("Baseline destination appeared during export; refusing replacement")
        stage.rename(destination)
    finally:
        if stage.exists():
            no_links(stage)
            if stage.resolve().parent != destination.parent.resolve() or not stage.name.startswith(".baseline-staging-"):
                raise ValueError("Refusing to remove a staging directory outside its explicit parent")
            shutil.rmtree(stage)
    return {"id": baseline_id, "manifestSha256": digest(manifest_raw), "files": len(files),
            "bytes": sum(len(raw) for raw, _ in files.values()), "promoted": False}


def verify_bundle(root, expected_manifest_sha256):
    if not SHA.fullmatch(expected_manifest_sha256):
        raise ValueError("An externally approved manifest digest is required")
    manifest_raw = read_bounded(relative_file(root, "manifest.json"))
    if digest(manifest_raw) != expected_manifest_sha256:
        raise ValueError("Baseline manifest differs from its approved digest")
    manifest = strict_json(manifest_raw)
    public_strings(manifest)
    if manifest.get("schema") != SCHEMA or manifest.get("upstreamCommit") != BASE:
        raise ValueError("Unknown baseline schema or upstream")
    payload, metadata = {}, {}
    total, raw_total = 0, 0
    for entry in manifest["files"]:
        name = entry["path"]
        if name in payload or type(entry.get("bytes")) is not int or not 0 <= entry["bytes"] <= MAX_FILE:
            raise ValueError("Duplicate file or invalid size")
        raw = read_bounded(relative_file(root, name))
        if len(raw) != entry["bytes"] or digest(raw) != entry["sha256"]:
            raise ValueError("Baseline member differs from its approved bytes: " + name)
        total += len(raw)
        if total > MAX_TOTAL:
            raise ValueError("Bundle total exceeds its bound")
        payload[name], metadata[name] = raw, entry
    actual_files = set()
    for path in root.rglob("*"):
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
            raise ValueError("Unexpected link in baseline bundle")
        if path.is_file():
            actual_files.add(path.relative_to(root).as_posix())
    if actual_files != set(payload) | {"manifest.json"}:
        raise ValueError("Unexpected or missing bundle members")
    if any(metadata[name]["kind"] != "canonical_asset" for name in manifest["assets"].values()):
        raise ValueError("Canonical assets refer to the wrong member kind")
    if metadata[manifest["referenceBuildIdentity"]]["kind"] != "build_identity":
        raise ValueError("Build identity refers to the wrong member kind")
    if metadata[manifest["referenceToolchainLock"]]["kind"] != "toolchain_lock":
        raise ValueError("Toolchain lock refers to the wrong member kind")
    assets = {location: payload[name] for location, name in manifest["assets"].items()}
    build_raw = payload[manifest["referenceBuildIdentity"]]
    verify_build_lock(build_raw, payload[manifest["referenceToolchainLock"]])
    matrix, loaded = set(), []
    for case in manifest["cases"]:
        key = (case["scenario"], case["model"])
        if key in matrix or key[0] not in SCENARIOS or key[1] not in MODELS or len(case["samples"]) != 3:
            raise ValueError("Unexpected baseline case matrix")
        matrix.add(key)
        if (len({sample["file"] for sample in case["samples"]}) != 3
                or len({sample["sha256"] for sample in case["samples"]}) != 3):
            raise ValueError("A/A captures were duplicated")
        values, raws = [], []
        for sample in case["samples"]:
            entry = metadata[sample["file"]]
            if entry["kind"] != "capture" or type(entry.get("rawBytes")) is not int:
                raise ValueError("Invalid compressed capture entry")
            raw = unpack_sample(payload[sample["file"]])
            raw_total += len(raw)
            if raw_total > MAX_TOTAL:
                raise ValueError("Expanded captures exceed total size bound")
            if len(raw) != entry["rawBytes"] or digest(raw) != entry["rawSha256"] or digest(raw) != sample["sha256"]:
                raise ValueError("Expanded capture differs from approved bytes")
            value = strict_json(raw)
            sample_contract(value, case["scenario"], case["model"])
            if reference_identity(value, build_raw) != manifest["runtimeIdentity"]:
                raise ValueError("Changed reference runtime identity")
            pinned_assets(value["actualAssetSha256"],
                          {name: digest(assets[name]) for name in value["actualAssetSha256"]}, assets)
            values.append(value)
            raws.append(raw)
        comparisons = [compare_aa.compare(values[0], value) for value in values[1:]]
        if any(value["status"] != "PASS_REPEATABILITY" for value in comparisons):
            raise ValueError("Original baseline repeatability failed")
        if comparisons != case["comparisons"]:
            raise ValueError("Original A/A report no longer agrees with its captures")
        loaded.append({"case": case, "reference": values[0], "raw": raws[0]})
    if matrix != {(scenario, model) for scenario in SCENARIOS for model in MODELS}:
        raise ValueError("Incomplete baseline model/contract matrix")
    return {"manifest": manifest, "cases": loaded, "assets": assets, "manifestSha256": digest(manifest_raw)}


def catalog_entry(baseline_id, catalog=None):
    catalog = catalog or PROBE / "baselines/catalog.json"
    data = strict_json(read_bounded(catalog))
    if data.get("schema") != "animation-baseline-catalog-v1":
        raise ValueError("Unknown baseline catalog")
    entry = data["baselines"][baseline_id]
    return relative_file(catalog.parent, entry["directory"]), entry["manifestSha256"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    for key in ("destination", "runtime", "upstream", "build-identity", "toolchain-lock"):
        export.add_argument("--" + key, type=Path, required=True)
    export.add_argument("--id", required=True)
    export.add_argument("--aa-report", type=Path, action="append", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--id")
    verify.add_argument("--bundle", type=Path)
    verify.add_argument("--expected-manifest-sha256")
    args = parser.parse_args()
    if args.command == "export":
        result = export_bundle(args.destination, args.id, args.aa_report, args.runtime, args.upstream, args.build_identity, args.toolchain_lock)
    else:
        if args.id:
            root, expected = catalog_entry(args.id)
        else:
            root, expected = args.bundle, args.expected_manifest_sha256
            if root is None or expected is None:
                parser.error("verify requires --id or --bundle and --expected-manifest-sha256")
        verified = verify_bundle(root, expected)
        result = {"passed": True, "id": verified["manifest"]["id"], "cases": len(verified["cases"]),
                  "original_samples_rechecked": 18, "rendering_verified": False}
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
