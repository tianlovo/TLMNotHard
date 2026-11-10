"""保留固定上游内置包及公共模型资源中尚未导入的文件。"""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from import_builtin_assets import GitBlobReader, UPSTREAM_COMMIT

PROJECT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = "src/main/resources/"
PACK_ROOT = RESOURCE_ROOT + "assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/"
MANIFEST = "assets/tlmnh/builtin-retention-provenance.json"
BASELINE_NAMES = (
    "banner-assets-provenance.json", "bed-assets-provenance.json", "builtin-assets-provenance.json",
    "builtin-sounds-provenance.json", "cabinet-assets-provenance.json", "core-assets-provenance.json",
    "gui-assets-provenance.json", "lifecycle-assets-provenance.json", "shrine-assets-provenance.json",
)
MODEL_DIRECTORIES = ("animation", "animations", "models/bedrock", "models/entity", "textures/bedrock", "textures/entity")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def in_scope(source):
    return source.startswith(PACK_ROOT) or (source.startswith(RESOURCE_ROOT)
        and any("/" + directory + "/" in source for directory in MODEL_DIRECTORIES))


def collect(upstream):
    paths = subprocess.check_output(
        ["git", "-C", str(upstream), "ls-tree", "-r", "--name-only", UPSTREAM_COMMIT, "src/main/resources"],
        text=True).splitlines()
    covered = set()
    baselines = []
    for name in BASELINE_NAMES:
        path = "assets/tlmnh/" + name
        raw = (PROJECT / RESOURCE_ROOT / path).read_bytes()
        summary = json.loads(raw)
        if summary.get("commit") != UPSTREAM_COMMIT:
            raise ValueError("Baseline is not anchored to the fixed upstream: " + path)
        entries = summary["files"]
        entries = entries.values() if isinstance(entries, dict) else entries
        covered.update(entry["source"] for entry in entries if entry.get("source"))
        baselines.append({"path": path, "sha256": digest(raw)})
    inventory, files, payloads = [], [], {}
    with GitBlobReader(upstream) as read:
        for source in sorted(path for path in paths if in_scope(path)):
            data = read(source)
            record = {"source": source, "bytes": len(data), "sha256": digest(data)}
            inventory.append(record)
            if source in covered:
                continue
            target = source.removeprefix(PACK_ROOT if source.startswith(PACK_ROOT) else RESOURCE_ROOT)
            # legacy_pack remains a separate resource-pack subtree, never an overlay on effective assets.
            if not (target.startswith("assets/") or target.startswith("legacy_pack/") or target in ("pack.mcmeta", "pack.png")):
                raise ValueError("Unexpected retention target: " + target)
            if target in ("pack.mcmeta", "pack.png"):
                # Preserve the 1.20 container metadata without activating it as this 1.7.10 JAR's root metadata.
                target = "assets/tlmnh/retained-builtin-pack/" + target
            if target in payloads:
                raise ValueError("Conflicting upstream retention target: " + target)
            payloads[target] = data
            files.append({"path": target, **record})
    if len(files) != 332 or sum(entry["bytes"] for entry in files) != 5538204:
        raise ValueError("Fixed retention inventory differs from the reviewed 332 original files")
    catalog = "assets/tlmnh/builtin-models.json"
    manifest = {
        "schema": 1, "upstream": "https://github.com/TartaricAcid/TouhouLittleMaid",
        "commit": UPSTREAM_COMMIT, "license": "CC-BY-NC-SA-4.0",
        "copyright": "Copyright (c) 2019-2025 tartaric_acid; original pack authors remain in source metadata",
        "changes": "Original bytes retained; bundled pack paths flattened; original pack.mcmeta/pack.png mapped to assets/tlmnh/retained-builtin-pack; legacy_pack paths unchanged; no new playable choices or integrations",
        "scope": {"bundled_pack": PACK_ROOT, "resource_root": RESOURCE_ROOT,
                  "outside_pack_directories": list(MODEL_DIRECTORIES),
                  "source_files": len(inventory), "source_inventory_sha256": digest(json_bytes(inventory)),
                  "selection": "All fixed Git-tree files in scope, minus sources already recorded by the baseline manifests"},
        "baseline_manifests": baselines,
        "catalog": {"path": catalog, "sha256": digest((PROJECT / RESOURCE_ROOT / catalog).read_bytes())},
        "files": files,
    }
    return manifest, payloads


def write(manifest, payloads):
    base = PROJECT / RESOURCE_ROOT
    target_manifest = base / MANIFEST
    if target_manifest.exists() and target_manifest.read_bytes() != json_bytes(manifest):
        raise ValueError("Existing retention manifest differs; refusing an unreviewed replacement")
    for target, data in payloads.items():
        destination = base / target
        destination.resolve().relative_to(base.resolve())
        if destination.exists() and (not destination.is_file() or destination.read_bytes() != data):
            raise ValueError("Refusing to overwrite existing effective or unknown resource: " + target)
    for target, data in payloads.items():
        destination = base / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    target_manifest.write_bytes(json_bytes(manifest))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    manifest, payloads = collect(args.upstream.resolve(strict=True))
    if args.write:
        write(manifest, payloads)
    report = {"passed": True, "upstream_commit": UPSTREAM_COMMIT, "files": len(payloads),
              "bytes": sum(map(len, payloads.values())), "manifest_sha256": digest(json_bytes(manifest)),
              "source_scope_files": manifest["scope"]["source_files"],
              "source_inventory_sha256": manifest["scope"]["source_inventory_sha256"],
              "scope": "original resource retention only; playable catalog and gameplay unchanged"}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_bytes(json_bytes(report))
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
