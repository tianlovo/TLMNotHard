"""Synthetic comparator protocol tests; these do not execute native or GPU geometry."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import compare as subject
import experiment as orchestration


def identity(reference):
    names = subject.REFERENCE_REQUIRED if reference else subject.PORT_REQUIRED
    definitions = {name: {"sha256": "a" * 64, "count": 1, "stage": subject.STAGE, "loaderClass": "synthetic-fixture-only"} for name in names}
    build = {"schema": "geometry-build-identity-v2" if reference else "port-geometry-build-v2",
             "contractSha256": subject.CONTRACT_SHA, "agentSha256": "b" * 64,
             "compiledClasses": {name: "a" * 64 for name in names}}
    if reference:
        for definition in definitions.values():
            definition["resourceSha256"] = "a" * 64
        build.update(upstreamCommit=subject.CONTRACT["upstreamCommit"], baseReferenceBuildIdentitySha256="c" * 64,
                     baseReferenceBuildIdentity={"schema": "reference-build-identity-v1", "status": "LOCK_VERIFIED", "upstreamCommit": subject.CONTRACT["upstreamCommit"], "java": {"synthetic": True}})
    result = {"build": build, "actualFirstDefinitions": definitions, "buildManifestSha256": "d" * 64,
              "runtimePolicy": "geometry-v2-two-passive-observers" if reference else "geometry-v2-single-passive-observer"}
    if reference:
        result.update(baseReferenceRuntimeVerified=False, baseReferenceBuildIdentityAnchored=True, runtimeJava={"synthetic": True})
    else:
        artifact = {"group": "it.unimi.dsi", "name": "fastutil", "version": "8.5.18", "fileName": "fastutil-8.5.18.jar",
                    "sha256": "e" * 64, "resolvedFile": str((Path("synthetic-artifacts") / "fastutil-8.5.18.jar").resolve())}
        build.update(dependencyPolicy=subject.FASTUTIL_POLICY, externalArtifacts={subject.FASTUTIL_COORDINATE: artifact},
                     externalClasses={name: {"artifact": subject.FASTUTIL_COORDINATE, "entry": name.replace(".", "/") + ".class", "sha256": "a" * 64}
                                      for name in subject.FASTUTIL_CLASSES})
        result.update(dependencyPolicy=subject.FASTUTIL_POLICY, actualExternalArtifacts=copy.deepcopy(build["externalArtifacts"]))
        for name in subject.FASTUTIL_CLASSES:
            definitions[name].update(artifact=subject.FASTUTIL_COORDINATE, artifactSha256=artifact["sha256"], resourceSha256="a" * 64,
                                     resourceEntry=name.replace(".", "/") + ".class", codeSourceFile=artifact["resolvedFile"],
                                     resourceArtifactFile=artifact["resolvedFile"])
    return result


def fixture(reference, backend="gecko"):
    model = next(name for name, data in subject.CONTRACT["models"].items() if data["backend"] == backend)
    tree = {"roots": ["root"], "nodes": {"root": {"parent": None, "position": [0, 0, 0], "pivot": [0, 0, 0],
            "rotationRadians": [0, 0, 0], "scale": [1, 1, 1], "hidden": False, "cubesHidden": False,
            "childrenHidden": False, "cubeCount": 1, "children": []}}}
    if backend == "bedrock":
        tree = [{"path": "root", "name": "root", "position": [0, 0, 0], "offset": [0, 0, 0], "rotationRadians": [0, 0, 0],
                 "scale": [1, 1, 1], "additionalQuaternion": [0, 0, 0, 1], "visible": True, "illuminated": False,
                 "mirror": False, "cubeCount": 1, "childCount": 0}]
    vertices = [[1, 0, 2, 0, 1, 0, 0, 0], [2, 0, 2, 0, 1, 0, 1, 0],
                [2, 1, 2, 0, 1, 0, 1, 1], [1, 1, 2, 0, 1, 0, 0, 1]]
    if not reference:
        vertices = [subject.canonical_vertex(row, backend) for row in vertices]
    samples = [{"repetition": i, "freshNativeParse": True, "nativeNormalBranch": "pose-normal-matrix",
                "bindTree": copy.deepcopy(tree), "vertices": copy.deepcopy(vertices), "plainVertices": copy.deepcopy(vertices),
                "freshPlainParse": True, "observerNeutral": True,
                "mapping": "actual-bone-cube-face-corner" if backend == "gecko" else "ordered-bedrock"} for i in range(3)]
    if backend == "gecko":
        for sample in samples:
            sample["boneFaces"] = {"root": [1]}
            sample["vertexKeys"] = [{"bone": "root", "cube": 0, "face": "down", "corner": i, "drawIndex": i} for i in range(4)]
    return {"schema": "geometry-reference-v2" if reference else "port-geometry-v2", "contract": subject.CONTRACT["contract"],
            "contractSha256": subject.CONTRACT_SHA, "model": model, "backend": backend,
            "coordinateContract": subject.CONTRACT["coordinateContract"],
            "coordinateSpace": "upstream-native-bind" if reference else "port-modelbase-bind",
            "geometrySha256": subject.CONTRACT["models"][model]["geometrySha256"],
            "textureSha256": subject.CONTRACT["models"][model]["textureSha256"], "worldRendererVerified": False,
            "gpuVerified": False, "dynamicAnimationVerified": False, "materialAttributesCompared": False,
            "orderDependentRenderingVerified": False, "rawAAStable": True, "observerNeutralityVerified": True,
            "samples": samples, "identity": identity(reference)}


def sync_plain(report):
    for sample in report["samples"]:
        sample["plainVertices"] = copy.deepcopy(sample["vertices"])


def siblings_fixture(reference, reverse=False):
    report = fixture(reference)
    source = fixture(True)["samples"][0]["vertices"]
    groups = {"A": copy.deepcopy(source), "B": [[v + 4 if i == 0 else v for i, v in enumerate(row)] for row in source]}
    order = ["B", "A"] if reverse else ["A", "B"]
    for sample in report["samples"]:
        template = copy.deepcopy(sample["bindTree"]["nodes"]["root"])
        sample["bindTree"]["nodes"]["root"]["cubeCount"] = 0
        sample["bindTree"]["nodes"]["root"]["children"] = list(order)
        for name in ("A", "B"):
            sample["bindTree"]["nodes"][name] = copy.deepcopy(template)
            sample["bindTree"]["nodes"][name]["parent"] = "root"
        sample["boneFaces"] = {"root": [], "A": [1], "B": [1]}
        sample["vertices"] = [list(row) if reference else subject.canonical_vertex(row, "gecko") for name in order for row in groups[name]]
        sample["vertexKeys"] = [{"bone": name, "cube": 0, "face": "down", "corner": corner, "drawIndex": index * 4 + corner}
                                for index, name in enumerate(order) for corner in range(4)]
    sync_plain(report)
    return report


class CompareTests(unittest.TestCase):
    def test_missing_fastutil_definition_or_jar_entry_is_rejected(self):
        for missing in ("definition", "compiled", "entry", "artifact"):
            with self.subTest(missing=missing):
                port = fixture(False)
                name = "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$MapIterator"
                if missing == "definition":
                    del port["identity"]["actualFirstDefinitions"][name]
                elif missing == "compiled":
                    del port["identity"]["build"]["compiledClasses"][name]
                elif missing == "entry":
                    del port["identity"]["build"]["externalClasses"][name]
                else:
                    del port["identity"]["build"]["externalArtifacts"][subject.FASTUTIL_COORDINATE]
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_fastutil_class_cannot_silently_come_from_another_jar(self):
        name = "it.unimi.dsi.fastutil.HashCommon"
        for field, wrong in (("codeSourceFile", "other-library.jar"), ("resourceArtifactFile", "shadow-fastutil.jar"),
                             ("artifactSha256", "0" * 64), ("resourceSha256", "0" * 64),
                             ("resourceEntry", "other/HashCommon.class"), ("artifact", "it.unimi.dsi:fastutil:8.5.9")):
            with self.subTest(field=field):
                port = fixture(False)
                port["identity"]["actualFirstDefinitions"][name][field] = wrong
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_resolved_dependency_version_and_artifact_digest_remain_bound(self):
        for field, wrong in (("version", "8.5.9"), ("sha256", "0" * 64), ("resolvedFile", "another-fastutil.jar")):
            with self.subTest(field=field):
                port = fixture(False)
                port["identity"]["build"]["externalArtifacts"][subject.FASTUTIL_COORDINATE][field] = wrong
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_raw_tree_classes_are_still_selected_production_definitions(self):
        for name in ("com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawBoneGroup",
                     "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree"):
            with self.subTest(name=name):
                port = fixture(False)
                del port["identity"]["actualFirstDefinitions"][name]
                del port["identity"]["build"]["compiledClasses"][name]
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_sibling_draw_order_is_separate_from_semantic_geometry(self):
        result = subject.compare(siblings_fixture(True), siblings_fixture(False, reverse=True))
        self.assertTrue(result["matched"])
        self.assertFalse(result["drawOrder"]["matched"])
        self.assertEqual(1, len(result["drawOrder"]["siblingOrderDifferences"]))
        self.assertFalse(result["orderDependentRenderingVerified"])

    def test_optional_order_policy_rejects_order_only_change_without_relabelling_geometry(self):
        result = subject.compare(siblings_fixture(True), siblings_fixture(False, reverse=True))
        original = copy.deepcopy(result)
        self.assertTrue(result["matched"])
        self.assertTrue(subject.submission_order_policy(result)["passed"])
        policy = subject.submission_order_policy(result, required=True)
        self.assertFalse(policy["passed"])
        self.assertEqual("SUBMISSION_ORDER_DIFFERENCE", policy["status"])
        self.assertEqual(original, result)

    def test_order_policy_requires_roots_siblings_and_actual_vertex_order_independently(self):
        baseline = subject.compare(siblings_fixture(True), siblings_fixture(False))
        self.assertTrue(subject.submission_order_policy(baseline, required=True)["passed"])
        for field, changed in (("rootOrderMatched", False), ("matched", False),
                               ("firstDifference", {"drawIndex": 0}),
                               ("siblingOrderDifferences", [{"bone": "root"}])):
            with self.subTest(field=field):
                result = copy.deepcopy(baseline)
                result["drawOrder"][field] = changed
                self.assertFalse(subject.submission_order_policy(result, required=True)["passed"])

    def test_order_policy_preserves_ordered_bedrock_scope_without_inventing_semantic_keys(self):
        result = subject.compare(fixture(True, "bedrock"), fixture(False, "bedrock"))
        original = copy.deepcopy(result)
        policy = subject.submission_order_policy(result, required=True)
        self.assertTrue(policy["passed"])
        self.assertEqual("ORDERED_BEDROCK_STREAM_MATCH", policy["status"])
        self.assertFalse(policy["orderDependentRenderingVerified"])
        self.assertNotIn("matched", result["drawOrder"])
        self.assertEqual(original, result)

    def test_order_policy_missing_unknown_or_malformed_evidence_fails_closed(self):
        baseline = subject.compare(fixture(True), fixture(False))
        for field in baseline["drawOrder"]:
            with self.subTest(missing=field):
                result = copy.deepcopy(baseline)
                del result["drawOrder"][field]
                policy = subject.submission_order_policy(result, required=True)
                self.assertFalse(policy["passed"])
                self.assertEqual("INCONCLUSIVE", policy["status"])
        for field, changed in (("schema", "geometry-comparison-v999"), ("model", "unknown:model"),
                               ("drawOrder", None), ("orderDependentRenderingVerified", True)):
            with self.subTest(field=field):
                result = copy.deepcopy(baseline)
                result[field] = changed
                self.assertFalse(subject.submission_order_policy(result, required=True)["passed"])
        for field, changed in (("matched", 1), ("rootOrderMatched", 1), ("siblingOrderDifferences", {})):
            with self.subTest(orderField=field):
                result = copy.deepcopy(baseline)
                result["drawOrder"][field] = changed
                self.assertFalse(subject.submission_order_policy(result, required=True)["passed"])
        bedrock = subject.compare(fixture(True, "bedrock"), fixture(False, "bedrock"))
        bedrock["drawOrder"]["scope"] = "unknown ordered stream"
        self.assertFalse(subject.submission_order_policy(bedrock, required=True)["passed"])

    def test_order_policy_cannot_override_a_geometry_failure(self):
        port = fixture(False)
        for sample in port["samples"]:
            sample["bindTree"]["nodes"]["root"]["pivot"][0] = 1
        result = subject.compare(fixture(True), port)
        self.assertTrue(result["drawOrder"]["matched"])
        self.assertFalse(result["matched"])
        policy = subject.submission_order_policy(result, required=True)
        self.assertFalse(policy["passed"])
        self.assertEqual("INCONCLUSIVE", policy["status"])

    def test_experiment_optional_order_gate_changes_admission_without_changing_geometry(self):
        # Protocol orchestration only: the native producers are not executed here
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "launcher.json"
            launcher.write_text(json.dumps({"schema": "geometry-replay-launcher-v2"}), encoding="utf-8")
            captures = {}
            for backend in ("gecko", "bedrock"):
                original = siblings_fixture(True) if backend == "gecko" else fixture(True, backend)
                path = root / (backend + "-synthetic-original.json")
                path.write_text(json.dumps(original), encoding="utf-8")
                captures[original["model"]] = path

            def send(_game, command, **options):
                if command == "status":
                    return {"worldLoaded": True, "spectator": True}
                path = captures[options["model"]]
                return {"localCapture": path, "captureSha256": orchestration.sha(path)}

            def replay(_launcher, original_file, destination, mode):
                original = json.loads(original_file.read_text(encoding="utf-8"))
                backend = original["backend"]
                port = siblings_fixture(False, reverse=True) if backend == "gecko" else fixture(False, backend)
                for sample in port["samples"]:
                    if mode == "mirror_x":
                        for vertex in sample["vertices"]:
                            vertex[0] *= -1
                            vertex[3] *= -1
                    elif mode == "uv_shift":
                        for vertex in sample["vertices"]:
                            vertex[6] += 0.125
                    elif mode == "corner_swap":
                        sample["vertices"][0], sample["vertices"][1] = sample["vertices"][1], sample["vertices"][0]
                sync_plain(port)
                destination.write_text(json.dumps(port), encoding="utf-8")
                return port

            with mock.patch.object(orchestration, "send", side_effect=send), mock.patch.object(orchestration, "replay", side_effect=replay):
                default = orchestration.run(root, launcher, root / "default")
                strict = orchestration.run(root, launcher, root / "strict", require_submission_order=True)
            self.assertTrue(default["passed"])
            self.assertEqual("NOT_REQUIRED", default["order_policy"]["status"])
            self.assertFalse(strict["passed"])
            self.assertEqual("SUBMISSION_ORDER_POLICY_FAILED", strict["verdict"])
            self.assertTrue(all(case["geometryPassed"] for case in strict["cases"]))
            gecko = next(case for case in strict["cases"] if "hanfu" in case["model"])
            bedrock = next(case for case in strict["cases"] if "hanfu" not in case["model"])
            self.assertFalse(gecko["order_policy"]["passed"])
            self.assertTrue(bedrock["order_policy"]["passed"])
            self.assertFalse(strict["orderDependentRenderingVerified"])

    def test_corner_swap_keeps_keys_but_changes_actual_written_values(self):
        for backend in ("gecko", "bedrock"):
            with self.subTest(backend=backend):
                port = fixture(False, backend)
                for sample in port["samples"]:
                    sample["vertices"][0], sample["vertices"][1] = sample["vertices"][1], sample["vertices"][0]
                sync_plain(port)
                result = subject.compare(fixture(True, backend), port)
                self.assertEqual("GEOMETRY_DIFFERENCE", result["verdict"])
                self.assertFalse(result["position"]["matched"])

    def test_missing_duplicate_or_invalid_semantic_vertices_rejected(self):
        for mode in ("duplicate", "missing", "face", "corner", "index", "bone"):
            with self.subTest(mode=mode):
                port = fixture(False)
                for sample in port["samples"]:
                    if mode == "duplicate":
                        sample["vertexKeys"][1]["corner"] = 0
                    elif mode == "missing":
                        sample["vertexKeys"].pop()
                    elif mode == "face":
                        sample["vertexKeys"][0]["face"] = "unknown"
                    elif mode == "corner":
                        sample["vertexKeys"][0]["corner"] = 4
                    elif mode == "index":
                        sample["vertexKeys"][0]["drawIndex"] = 1
                    else:
                        sample["vertexKeys"][0]["bone"] = "unknown"
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_mesh_masks_require_all_and_only_real_faces(self):
        for mask in (0, 3, 128):
            with self.subTest(mask=mask):
                port = fixture(False)
                for sample in port["samples"]:
                    sample["boneFaces"]["root"] = [mask]
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_face_corner_order_is_not_silently_canonicalized(self):
        port = fixture(False)
        for sample in port["samples"]:
            sample["vertexKeys"][0]["corner"] = 1
            sample["vertexKeys"][1]["corner"] = 0
        self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_missing_actual_bone_callback_rejected_even_for_zero_cube_root(self):
        port = siblings_fixture(False)
        for sample in port["samples"]:
            del sample["boneFaces"]["root"]
        self.assertEqual("INCONCLUSIVE", subject.compare(siblings_fixture(True), port)["verdict"])

    def test_raw_stability_and_neutrality_labels_do_not_replace_evidence(self):
        for field in ("rawAAStable", "observerNeutralityVerified"):
            with self.subTest(field=field):
                port = fixture(False)
                del port[field]
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_plain_trace_neutrality_is_checked_independently_of_aa(self):
        for mode in ("missing", "values", "flag"):
            with self.subTest(mode=mode):
                port = fixture(False)
                for sample in port["samples"]:
                    if mode == "missing":
                        del sample["plainVertices"]
                    elif mode == "values":
                        sample["plainVertices"][0][0] += 1
                    else:
                        sample["observerNeutral"] = False
                self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_old_v1_schema_is_not_retagged_as_v2(self):
        reference = fixture(True)
        reference["schema"] = "geometry-reference-v1"
        self.assertEqual("INCONCLUSIVE", subject.compare(reference, fixture(False))["verdict"])

    def test_only_provenance_verified_lf_and_mechanically_derived_crlf_are_approved(self):
        project = Path(__file__).resolve().parents[2]
        provenance = json.loads((project / "src/main/resources/assets/tlmnh/builtin-assets-provenance.json").read_text(encoding="utf-8"))
        self.assertEqual("canonical-lf-or-full-crlf-v1", subject.CONTRACT["referenceGeometryNewlineContract"])
        for model in subject.CONTRACT["models"].values():
            domain, path = model["geometry"].split(":", 1)
            filename = "src/main/resources/assets/" + domain + "/" + path
            raw = (project / filename).read_bytes()
            canonical = hashlib.sha256(raw).hexdigest()
            self.assertEqual(provenance["files"][filename]["sha256"], canonical)
            self.assertEqual(model["geometrySha256"], canonical)
            self.assertNotIn(b"\r", raw)
            self.assertIn(b"\n", raw)
            self.assertEqual({"LF": canonical, "CRLF": hashlib.sha256(raw.replace(b"\n", b"\r\n")).hexdigest()},
                             model["referenceGeometrySha256"])

    def test_reference_crlf_acceptance_keeps_both_actual_digests(self):
        for backend in ("gecko", "bedrock"):
            with self.subTest(backend=backend):
                reference, port = fixture(True, backend), fixture(False, backend)
                actual = subject.CONTRACT["models"][reference["model"]]["referenceGeometrySha256"]["CRLF"]
                reference["geometrySha256"] = actual
                result = subject.compare(reference, port)
                self.assertTrue(result["matched"])
                self.assertEqual("CRLF", result["assets"]["geometry"]["referenceNewlines"])
                self.assertEqual(actual, result["assets"]["geometry"]["referenceActualSha256"])
                self.assertEqual(port["geometrySha256"], result["assets"]["geometry"]["portActualSha256"])
                self.assertNotEqual(actual, port["geometrySha256"])
                self.assertEqual(actual, reference["geometrySha256"])

    def test_port_still_rejects_crlf_even_when_reference_has_the_same_digest(self):
        for backend in ("gecko", "bedrock"):
            with self.subTest(backend=backend):
                reference, port = fixture(True, backend), fixture(False, backend)
                actual = subject.CONTRACT["models"][reference["model"]]["referenceGeometrySha256"]["CRLF"]
                reference["geometrySha256"] = port["geometrySha256"] = actual
                self.assertEqual("INCONCLUSIVE", subject.compare(reference, port)["verdict"])

    def test_mixed_newlines_and_other_json_changes_remain_rejected(self):
        project = Path(__file__).resolve().parents[2]
        for backend in ("gecko", "bedrock"):
            model = next(spec for spec in subject.CONTRACT["models"].values() if spec["backend"] == backend)
            domain, path = model["geometry"].split(":", 1)
            raw = (project / "src/main/resources/assets" / domain / path).read_bytes()
            self.assertEqual(model["geometrySha256"], hashlib.sha256(raw).hexdigest())
            for kind, modified in (("mixed", raw.replace(b"\n", b"\r\n", 1)),
                                   ("other whitespace", raw + b" "),
                                   ("crlf plus change", raw.replace(b"\n", b"\r\n") + b" ")):
                with self.subTest(backend=backend, change=kind):
                    actual = hashlib.sha256(modified).hexdigest()
                    reference, port = fixture(True, backend), fixture(False, backend)
                    reference["geometrySha256"] = actual
                    self.assertEqual("INCONCLUSIVE", subject.compare(reference, port)["verdict"])
                    port["geometrySha256"] = actual
                    self.assertEqual("INCONCLUSIVE", subject.compare(reference, port)["verdict"])

    def test_original_transformed_definition_is_recorded_separately_from_bound_resource(self):
        reference = fixture(True)
        definition = next(iter(reference["identity"]["actualFirstDefinitions"].values()))
        definition["sha256"] = "9" * 64
        result = subject.compare(reference, fixture(False))
        self.assertTrue(result["matched"])
        self.assertEqual("9" * 64, definition["sha256"])
        self.assertEqual("a" * 64, definition["resourceSha256"])

    def test_original_changed_or_missing_class_resource_is_rejected(self):
        for missing in (True, False):
            with self.subTest(missing=missing):
                reference = fixture(True)
                definition = next(iter(reference["identity"]["actualFirstDefinitions"].values()))
                if missing:
                    del definition["resourceSha256"]
                else:
                    definition["resourceSha256"] = "0" * 64
                self.assertEqual("INCONCLUSIVE", subject.compare(reference, fixture(False))["verdict"])

    def test_duplicate_definition_rejected_on_both_sides(self):
        for reference_side in (True, False):
            with self.subTest(reference=reference_side):
                reference, port = fixture(True), fixture(False)
                definition = next(iter((reference if reference_side else port)["identity"]["actualFirstDefinitions"].values()))
                definition["count"] = 2
                self.assertEqual("INCONCLUSIVE", subject.compare(reference, port)["verdict"])

    def test_port_defined_bytes_remain_strictly_bound_to_compilation(self):
        port = fixture(False)
        definition = next(iter(port["identity"]["actualFirstDefinitions"].values()))
        definition["resourceSha256"] = "a" * 64
        definition["sha256"] = "9" * 64
        self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_missing_definition_digest_loader_or_wrong_stage_rejected(self):
        for field, bad in (("sha256", "UNAVAILABLE"), ("loaderClass", ""), ("loaderClass", 1), ("stage", "resource-only")):
            with self.subTest(field=field, bad=bad):
                reference = fixture(True)
                next(iter(reference["identity"]["actualFirstDefinitions"].values()))[field] = bad
                self.assertEqual("INCONCLUSIVE", subject.compare(reference, fixture(False))["verdict"])

    def test_asset_pins_match_existing_fixed_upstream_provenance(self):
        project = Path(__file__).resolve().parents[2]
        provenance = json.loads((project / "src/main/resources/assets/tlmnh/builtin-assets-provenance.json").read_text(encoding="utf-8"))
        self.assertEqual(subject.CONTRACT["upstreamCommit"], provenance["commit"])
        for model in subject.CONTRACT["models"].values():
            for kind in ("geometry", "texture"):
                domain, path = model[kind].split(":", 1)
                entry = provenance["files"]["src/main/resources/assets/" + domain + "/" + path]
                self.assertEqual(entry["sha256"], model[kind + "Sha256"])

    def test_same_resource_change_on_both_sides_is_not_an_approved_baseline(self):
        for backend in ("gecko", "bedrock"):
            for key in ("geometrySha256", "textureSha256"):
                with self.subTest(backend=backend, key=key):
                    reference, port = fixture(True, backend), fixture(False, backend)
                    reference[key] = port[key] = "0" * 64
                    result = subject.compare(reference, port)
                    self.assertFalse(result["matched"])
                    self.assertEqual("INCONCLUSIVE", result["verdict"])
                    self.assertIn("approved fixed upstream", result["error"])

    def test_each_side_must_use_the_approved_assets(self):
        for reference_side in (True, False):
            for key in ("geometrySha256", "textureSha256"):
                with self.subTest(reference=reference_side, key=key):
                    reference, port = fixture(True), fixture(False)
                    (reference if reference_side else port)[key] = "0" * 64
                    result = subject.compare(reference, port)
                    self.assertFalse(result["matched"])
                    self.assertEqual("INCONCLUSIVE", result["verdict"])

    def test_declared_basis_both_backends(self):
        for backend in ("gecko", "bedrock"):
            self.assertTrue(subject.compare(fixture(True, backend), fixture(False, backend))["matched"])

    def test_real_values_determine_failure_not_fault_label(self):
        port = fixture(False)
        port["fault"] = "mirror_x"
        self.assertTrue(subject.compare(fixture(True), port)["matched"])
        for sample in port["samples"]:
            for row in sample["vertices"]:
                row[0] *= -1
        sync_plain(port)
        result = subject.compare(fixture(True), port)
        self.assertEqual("GEOMETRY_DIFFERENCE", result["verdict"])
        self.assertFalse(result["position"]["matched"])

    def test_uv_and_recovery(self):
        port = fixture(False)
        for sample in port["samples"]:
            for row in sample["vertices"]:
                row[6] += 0.125
        sync_plain(port)
        result = subject.compare(fixture(True), port)
        self.assertFalse(result["uv"]["matched"])
        self.assertTrue(result["position"]["matched"])
        self.assertTrue(subject.compare(fixture(True), fixture(False))["matched"])

    def test_missing_fields_never_pass(self):
        port = fixture(False)
        del port["samples"][0]["bindTree"]["nodes"]["root"]["pivot"]
        self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_aa_drift_never_passes(self):
        original = fixture(True)
        original["samples"][2]["vertices"][0][0] += 0.01
        self.assertEqual("INCONCLUSIVE", subject.compare(original, fixture(False))["verdict"])

    def test_nonfinite_and_wrong_identity_never_pass(self):
        for mode in ("nan", "identity"):
            port = fixture(False)
            if mode == "nan":
                port["samples"][0]["vertices"][0][0] = float("nan")
            else:
                next(iter(port["identity"]["actualFirstDefinitions"].values()))["sha256"] = "0" * 64
            self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_missing_quads_is_geometry_difference(self):
        port = fixture(False)
        for sample in port["samples"]:
            sample["vertices"] *= 2
        sync_plain(port)
        self.assertEqual("INCONCLUSIVE", subject.compare(fixture(True), port)["verdict"])

    def test_bind_pivot_difference_not_overwritten(self):
        port = fixture(False)
        for sample in port["samples"]:
            sample["bindTree"]["nodes"]["root"]["pivot"][0] = 1
        result = subject.compare(fixture(True), port)
        self.assertEqual("GEOMETRY_DIFFERENCE", result["verdict"])
        self.assertFalse(result["bindTreeMatched"])


if __name__ == "__main__":
    unittest.main()
