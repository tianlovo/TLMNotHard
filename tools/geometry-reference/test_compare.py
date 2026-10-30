"""Synthetic comparator protocol tests; these do not execute native or GPU geometry."""

import copy
import json
from pathlib import Path
import unittest

import compare as subject


def identity(reference):
    names = subject.REFERENCE_REQUIRED if reference else subject.PORT_REQUIRED
    definitions = {name: {"sha256": "a" * 64, "count": 1, "stage": subject.STAGE, "loaderClass": "synthetic-fixture-only"} for name in names}
    build = {"schema": "geometry-build-identity-v1" if reference else "port-geometry-build-v1",
             "contractSha256": subject.CONTRACT_SHA, "agentSha256": "b" * 64,
             "compiledClasses": {name: "a" * 64 for name in names}}
    if reference:
        build.update(upstreamCommit=subject.CONTRACT["upstreamCommit"], baseReferenceBuildIdentitySha256="c" * 64,
                     baseReferenceBuildIdentity={"schema": "reference-build-identity-v1", "status": "LOCK_VERIFIED", "upstreamCommit": subject.CONTRACT["upstreamCommit"], "java": {"synthetic": True}})
    result = {"build": build, "actualFirstDefinitions": definitions, "buildManifestSha256": "d" * 64,
              "runtimePolicy": "geometry-v1-two-passive-observers" if reference else "geometry-v1-single-passive-observer"}
    if reference:
        result.update(baseReferenceRuntimeVerified=False, baseReferenceBuildIdentityAnchored=True, runtimeJava={"synthetic": True})
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
                "bindTree": copy.deepcopy(tree), "vertices": copy.deepcopy(vertices)} for i in range(3)]
    return {"schema": "geometry-reference-v1" if reference else "port-geometry-v1", "contract": subject.CONTRACT["contract"],
            "contractSha256": subject.CONTRACT_SHA, "model": model, "backend": backend,
            "coordinateContract": subject.CONTRACT["coordinateContract"],
            "coordinateSpace": "upstream-native-bind" if reference else "port-modelbase-bind",
            "geometrySha256": subject.CONTRACT["models"][model]["geometrySha256"],
            "textureSha256": subject.CONTRACT["models"][model]["textureSha256"], "worldRendererVerified": False,
            "gpuVerified": False, "dynamicAnimationVerified": False, "materialAttributesCompared": False,
            "samples": samples, "identity": identity(reference)}


class CompareTests(unittest.TestCase):
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
        result = subject.compare(fixture(True), port)
        self.assertEqual("GEOMETRY_DIFFERENCE", result["verdict"])
        self.assertFalse(result["position"]["matched"])

    def test_uv_and_recovery(self):
        port = fixture(False)
        for sample in port["samples"]:
            for row in sample["vertices"]:
                row[6] += 0.125
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
        self.assertEqual("GEOMETRY_DIFFERENCE", subject.compare(fixture(True), port)["verdict"])

    def test_bind_pivot_difference_not_overwritten(self):
        port = fixture(False)
        for sample in port["samples"]:
            sample["bindTree"]["nodes"]["root"]["pivot"][0] = 1
        result = subject.compare(fixture(True), port)
        self.assertEqual("GEOMETRY_DIFFERENCE", result["verdict"])
        self.assertFalse(result["bindTreeMatched"])


if __name__ == "__main__":
    unittest.main()
