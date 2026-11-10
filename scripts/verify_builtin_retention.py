"""核对完整保留清单及既有有效模型资源；源码和产物检查均无需上游 checkout 或导入。"""

import argparse
import collections
import hashlib
import json
import sys
import zipfile
from pathlib import Path, PurePosixPath

PROJECT = Path(__file__).resolve().parents[1]
MANIFEST = "assets/tlmnh/builtin-retention-provenance.json"
MANIFEST_SHA256 = "9829ef13de6c8134c947df3e3e2568805a075a2075f3737a27eac22674cafe90"
UPSTREAM_COMMIT = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_member(name):
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and ".." not in path.parts
            and "\\" not in name and ":" not in name and path.as_posix() == name,
            "Unsafe retained resource path: " + name)


def verify(read, names):
    counts = collections.Counter(names)
    raw = read(MANIFEST)
    require(digest(raw) == MANIFEST_SHA256, "Retention authority manifest differs from the fixed Git-tree inventory")
    manifest = json.loads(raw)
    require(manifest["schema"] == 1 and manifest["commit"] == UPSTREAM_COMMIT
            and manifest["license"] == "CC-BY-NC-SA-4.0", "Unexpected retention source schema, commit or license")
    require(counts[MANIFEST] == 1, "Missing or duplicate retention manifest")
    recorded = set()

    def check(path, entry):
        safe_member(path)
        require(counts[path] == 1, "Missing or duplicate retained/protected resource: " + path)
        data = read(path)
        require(digest(data) == entry["sha256"] and ("bytes" not in entry or len(data) == entry["bytes"]),
                "Retained/protected resource bytes differ: " + path)

    for entry in manifest["files"]:
        path = entry["path"]
        require(path not in recorded, "Duplicate retention declaration: " + path)
        recorded.add(path)
        check(path, entry)
    require(len(recorded) == 332 and sum(entry["bytes"] for entry in manifest["files"]) == 5538204,
            "Expected all 332 reviewed original files")
    check(manifest["catalog"]["path"], manifest["catalog"])
    protected = set()
    for baseline in manifest["baseline_manifests"]:
        check(baseline["path"], baseline)
        # Audio already has an independent complete byte/event gate; do not duplicate its resource scan.
        if baseline["path"].endswith("builtin-sounds-provenance.json"):
            continue
        entries = json.loads(read(baseline["path"]))["files"]
        entries = entries.items() if isinstance(entries, dict) else ((entry["path"], entry) for entry in entries)
        for target, entry in entries:
            path = target.removeprefix("src/main/resources/")
            if path.startswith("licenses/"):
                path = "META-INF/" + path
            require(path not in recorded, "Retained resource would override an existing effective asset: " + path)
            check(path, entry)
            protected.add(path)
    return {"passed": True, "retained_files": len(recorded), "retained_bytes": 5538204,
            "protected_files": len(protected), "upstream_commit": UPSTREAM_COMMIT,
            "source_scope_files": manifest["scope"]["source_files"],
            "source_inventory_sha256": manifest["scope"]["source_inventory_sha256"],
            "manifest_sha256": digest(raw), "scope": "fixed original bytes and catalog protection; not gameplay or rendering"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.artifact:
            with zipfile.ZipFile(args.artifact) as archive:
                report = verify(archive.read, archive.namelist())
        else:
            base = PROJECT / "src/main/resources"
            names = [path.relative_to(base).as_posix() for path in base.rglob("*") if path.is_file()]
            names += ["META-INF/licenses/" + path.name for path in (PROJECT / "licenses").iterdir() if path.is_file()]
            report = verify(lambda name: (PROJECT / name.removeprefix("META-INF/")).read_bytes()
                            if name.startswith("META-INF/licenses/") else (base / name).read_bytes(), names)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print("Builtin retention verification failed: " + str(error), file=sys.stderr)
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
