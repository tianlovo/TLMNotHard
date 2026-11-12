"""Create isolated client/server TLM overlay ZIPs from an audited, source-bound candidate."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
import audit_candidate_distribution as candidate_audit
from distribution import update

PROJECT = Path(__file__).resolve().parents[1]


def source_binding(snapshot_path, source_root, candidate_sha):
    raw = snapshot_path.read_bytes()
    snapshot = json.loads(raw)
    update.require(snapshot.get("candidate_sha256") == candidate_sha, "Frozen snapshot does not bind this candidate SHA-256")
    sources = update.hash_map(snapshot.get("main_source_sha256"))
    update.require(bool(sources) and snapshot.get("main_source_files") == len(sources), "Frozen source count differs from its inventory")
    update.require(all(name.startswith("src/main/") for name in sources), "Frozen snapshot contains a path outside src/main")
    source_root = source_root.resolve(strict=True)
    actual = {path.relative_to(source_root).as_posix() for path in (source_root / "src/main").rglob("*") if path.is_file()}
    update.require(actual == set(sources), "Frozen src/main file inventory differs from the snapshot")
    for name, expected in sources.items():
        path = update.safe(source_root, name)
        update.require(update.sha(path) == expected, "Frozen source bytes differ: " + name)
    return dict(schema="tlmnh-source-inputs-v1", candidate_sha256=candidate_sha,
                snapshot_sha256=update.digest(raw), main_source_files=len(sources), main_source_sha256=sources,
                scope="Exact declared frozen src/main inventory and byte hashes; external candidate association, not a reproducible-build attestation")


def old_jar(path):
    update.require(re.fullmatch(r"tlmnh-[A-Za-z0-9._-]+\.jar", path.name), "Previous JAR must retain a TLM filename")
    data = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        update.require(archive.namelist().count("mcmod.info") == 1, "Ambiguous previous mod metadata")
        root = json.loads(archive.read("mcmod.info"))
        mods = root if isinstance(root, list) else root.get("modList", [])
        update.require(len(mods) == 1 and mods[0].get("modid") == "tlmnh" and mods[0].get("mcversion") == "1.7.10",
                       "Previous artifact is not one TLM Minecraft 1.7.10 mod")
    return path.name, update.digest(data)


def archive_bytes(contents):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(contents.items()):
            update.member(name)
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
    return stream.getvalue()


def packages(artifact, expected_sha, snapshot_path, source_root, previous_jars=(), channel="local-validation", release_approval=None):
    data = artifact.read_bytes()
    update.require(update.digest(data) == expected_sha, "Candidate SHA-256 differs from the explicitly selected artifact")
    binding = source_binding(snapshot_path, source_root, expected_sha)
    report = candidate_audit.audit(artifact, data)
    update.require(report["integrity_passed"], "Candidate integrity failed: " + ", ".join(
        name for name, result in report["checks"].items() if not result["passed"]))
    update.require(channel in ("local-validation", "release"), "Unknown package channel")
    original = report["checks"]["original_code_license"]["observed"]
    if original["status"] == "legacy_undetermined":
        update.require(original.get("legacy_overlay_allowed") is True,
                       "This historical artifact is audit-only; a new package requires the declared project GPL license")
        update.require(channel == "local-validation"
                       and binding["snapshot_sha256"] == original["legacy_source_snapshot_sha256"],
                       "Historical license reading requires its exact original source snapshot and local-validation channel")
    sidecar_names = ("SOURCE_PROVENANCE.md", "docs/USER_GUIDE.md", "docs/USER_GUIDE.en.md",
                     "scripts/distribution/update.py", "scripts/distribution/README.md")
    sidecars = {name: (PROJECT / name).read_bytes() for name in sidecar_names}
    # Source guides link to the root LICENSE; packaged guides sit beside the licenses directory.
    for guide in ("docs/USER_GUIDE.md", "docs/USER_GUIDE.en.md"):
        sidecars[guide] = sidecars[guide].replace(
            b"[LICENSE](../LICENSE)", b"[LICENSE](../licenses/TLMNH-LICENSE.txt)")
    document_hashes = {name: update.digest(raw) for name, raw in sidecars.items()}
    document_hashes["requirements.txt"] = update.digest(b"rich\n")
    if channel == "release":
        report = candidate_audit.approve_release(report, data, release_approval, binding, document_hashes)
        update.require(report.get("distributable") is True and report.get("release_acceptance_evaluated") is True
                       and report.get("release_blockers") == [], "Release is not admitted: " + ", ".join(
                           blocker["code"] for blocker in report.get("release_blockers", [])))
    version = report["checks"]["metadata"]["observed"]["version"]
    jar_name = "tlmnh-" + version + ".jar"
    old = {}
    for path in previous_jars:
        name, value = old_jar(path)
        old.setdefault(name, set()).add(value)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        docs = {"THIRD_PARTY_NOTICES.md": archive.read("META-INF/THIRD_PARTY_NOTICES.md")}
        if candidate_audit.ORIGINAL_LICENSE_MEMBER in archive.namelist():
            docs["licenses/TLMNH-LICENSE.txt"] = archive.read(candidate_audit.ORIGINAL_LICENSE_MEMBER)
        for name in candidate_audit.LICENSES:
            docs["licenses/" + name] = archive.read("META-INF/licenses/" + name)
        for name in report["checks"]["licenses_and_source_anchors"]["observed"]["provenance_manifests"]:
            docs["provenance/" + name] = archive.read(name)
        for name in ("assets/tlmnh/builtin-models.json", "assets/tlmnh/builtin-sounds.json"):
            docs["provenance/" + name] = archive.read(name)
        audio_source_status = json.loads(archive.read("assets/tlmnh/builtin-sounds-provenance.json"))["audio_distribution_cleared"]
    if channel == "release":
        decision = report["release_approval"]
        docs["release-decision.json"] = update.encoded(decision)
    docs["source-reference/SOURCE_PROVENANCE.md"] = sidecars["SOURCE_PROVENANCE.md"]
    for guide in ("USER_GUIDE.md", "USER_GUIDE.en.md"):
        docs["guides/" + guide] = sidecars["docs/" + guide]
    docs["source-inputs.json"] = update.encoded(binding)
    docs["audit-report.json"] = update.encoded(report)
    docs["STATUS.txt"] = ("INDEV LOCAL VALIDATION ONLY\nNot a formal release or distribution clearance.\n"
                          "All candidate resources, including audio, are retained.\n" if channel == "local-validation"
                          else "Release package; refer to the attached acceptance and source records.\n").encode("utf-8")
    updater = sidecars["scripts/distribution/update.py"]
    readme = sidecars["scripts/distribution/README.md"]
    result = {}
    for kind in ("client", "server"):
        jar_path = update.mod_prefix(kind) + jar_name
        doc_root = ".tlmnh-distribution/" + kind + "/"
        payload = {jar_path: data, **{doc_root + name: content for name, content in docs.items()}}
        remove, replace = {}, {}
        for name, values in old.items():
            prior_path = update.mod_prefix(kind) + name
            values = sorted(values - ({expected_sha} if prior_path == jar_path else set()))
            if values:
                (replace if prior_path == jar_path else remove)[prior_path] = values
        manifest = dict(schema=update.SCHEMA, scope="tlmnh", kind=kind, channel=channel,
                        status="local-validation" if channel == "local-validation" else "ready",
                        distributable=channel == "release", version=version, minecraft="1.7.10", target_environment="GTNH 2.9.0",
                        jar=dict(path=jar_path, sha256=expected_sha), files={name: update.digest(content) for name, content in payload.items()},
                        patches={}, remove_files=remove, replace_files=replace, audio_distribution_cleared=channel == "release",
                        audio_source_distribution_cleared=audio_source_status,
                        release_blockers=report["release_blockers"], source_input_snapshot_sha256=binding["snapshot_sha256"],
                        source_reference_scope="Included checkout attribution reference; exact src/main input hashes recorded separately",
                        client_server_jar_identical=True)
        contents = {**payload, "manifest.json": update.encoded(manifest), "update.py": updater,
                    "requirements.txt": b"rich\n", "README.md": readme}
        contents["SHA256SUMS.txt"] = "".join(update.digest(content) + "  " + name + "\n"
                                             for name, content in sorted(contents.items())).encode("utf-8")
        result[kind] = (f"tlmnh-{version}-{kind}-{channel}-{expected_sha[:12]}.zip", archive_bytes(contents))
    return result, report, binding


def exclusive_publish(source, destination):
    if os.name == "nt":
        os.rename(source, destination)  # Native Windows rename refuses an existing destination.
    else:
        os.link(source, destination)  # POSIX rename would replace an existing destination.


def publish_archives(output, results):
    """Stage both archives before publishing; retry exact existing bytes, never overwrite unknown successors."""
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    targets = {kind: update.safe(output, name) for kind, (name, data) in results.items()}
    for kind, (_, data) in results.items():
        path = targets[kind]
        update.require(not path.exists() or path.is_file() and update.sha(path) == update.digest(data),
                       "Existing package has unknown bytes; retained: " + path.name)
    archives = [dict(kind=kind, file=name, sha256=update.digest(data), bytes=len(data))
                for kind, (name, data) in results.items()]
    if all(path.exists() for path in targets.values()):
        return archives
    stage = Path(tempfile.mkdtemp(prefix=".tlmnh-package-", dir=output))
    staged, published = [], []

    def identity(path):
        stat = path.stat()
        return stat.st_dev, stat.st_ino

    stage_identity = identity(stage)

    def cleanup(records):
        for path, expected, file_id in reversed(records):
            try:
                # An externally replaced/edited file is retained, even under the private staging directory.
                actual = update.safe(output, path.relative_to(output).as_posix())
                if actual.is_file() and identity(actual) == file_id and update.sha(actual) == expected:
                    actual.unlink()
            except (OSError, ValueError):
                pass
        try:
            directory = update.safe(output, stage.name)
            if directory.is_dir() and identity(directory) == stage_identity:
                directory.rmdir()  # Only our still-identical empty directory; never recursive cleanup.
        except (OSError, ValueError):
            pass

    try:
        for kind, (_, data) in results.items():
            path = update.safe(output, stage.name + "/" + kind + ".tmp")
            with path.open("xb") as stream:
                staged.append((path, update.digest(data), identity(path)))
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            update.require(update.sha(path) == update.digest(data), "Staged package readback differs")
        for (kind, (_, data)), (path, expected, file_id) in zip(results.items(), staged):
            destination = targets[kind]
            try:
                # Complete same-volume bytes are published without replacing an existing path.
                exclusive_publish(path, destination)
                published.append((destination, expected, file_id))
            except FileExistsError:
                destination = update.safe(output, destination.name)
                update.require(destination.is_file() and update.sha(destination) == expected,
                               "Concurrent package has unknown bytes; retained: " + destination.name)
            update.require(update.sha(destination) == expected, "Published package readback differs")
        return archives
    except BaseException:
        cleanup(published)
        raise
    finally:
        cleanup(staged)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--input-snapshot", required=True, type=Path)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--previous-jar", type=Path, action="append", default=[])
    parser.add_argument("--channel", choices=("local-validation", "release"), default="local-validation")
    parser.add_argument("--release-approval", type=Path, help="Explicit artifact-bound approval; required only for formal release")
    parser.add_argument("--output", type=Path, default=PROJECT / "dist")
    args = parser.parse_args()
    try:
        update.require(re.fullmatch(r"[0-9a-f]{64}", args.sha256), "Expected SHA-256 must be lowercase hex")
        results, report, binding = packages(args.artifact, args.sha256, args.input_snapshot, args.source_root, args.previous_jar, args.channel, args.release_approval)
        archives = publish_archives(args.output, results)
        # Deliberately no local artifact/source/runtime paths in the portable report or ZIP.
        print(json.dumps(dict(schema="tlmnh-overlay-package-result-v1", channel=args.channel,
                              distributable=args.channel == "release", candidate_sha256=args.sha256,
                              source_files=binding["main_source_files"], integrity_passed=report["integrity_passed"],
                              release_blockers=report["release_blockers"], archives=archives), ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RuntimeError, zipfile.BadZipFile) as error:
        message = error.strerror if isinstance(error, OSError) else str(error)
        print(json.dumps(dict(distributable=False, error=message), ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
