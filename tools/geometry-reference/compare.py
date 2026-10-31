"""Strict native-bind geometry comparison; no oracle geometry implementation."""

import argparse
import hashlib
import json
import math
from pathlib import Path

CONTRACT_FILE = Path(__file__).with_name("contract.json")
CONTRACT = json.loads(CONTRACT_FILE.read_text(encoding="utf-8"))
CONTRACT_SHA = hashlib.sha256(CONTRACT_FILE.read_bytes()).hexdigest()
STAGE = "premain-transformer-input-after-defining-loader-transformations"
FASTUTIL_COORDINATE = "it.unimi.dsi:fastutil:8.5.18"
FASTUTIL_POLICY = "resolved-fastutil-8.5.18-v1"
FASTUTIL_CLASSES = {
    "it.unimi.dsi.fastutil.HashCommon",
    "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap",
    "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap$MapIterator",
}
REFERENCE_REQUIRED = {
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoMesh",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawBoneGroup",
    "com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubePerFace",
    "com.tianluoqaq.tlmnh.geometry.GeometrySnapshots",
    "com.tianluoqaq.tlmnh.geometry.GeometryTrace",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe$Sink",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe$1",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe$2",
}
PORT_REQUIRED = {
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels",
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel",
    "com.tianluoqaq.tlmnh.client.render.LegacyPoseStack",
    "com.tianluoqaq.tlmnh.client.render.BedrockPose",
    "com.tianluoqaq.tlmnh.client.render.GeometryBoneObserver",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoMesh",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawBoneGroup",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubePerFace",
    "com.tianluoqaq.tlmnh.geometry.GeometrySnapshots",
    "com.tianluoqaq.tlmnh.geometry.GeometryTrace",
    "com.tianluoqaq.tlmnh.geometry.replay.GeometryPortReplay",
    "com.tianluoqaq.tlmnh.geometry.replay.GeometryPortReplay$Sink",
    "com.tianluoqaq.tlmnh.geometry.replay.GeometryPortReplay$PlainSink",
}
PORT_REQUIRED.update(FASTUTIL_CLASSES)


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def finite_tree(value):
    if isinstance(value, dict):
        for child in value.values():
            finite_tree(child)
    elif isinstance(value, list):
        for child in value:
            finite_tree(child)
    elif type(value) in (int, float):
        require(number(value), "Non-finite tree value")
    else:
        require(value is None or isinstance(value, (str, bool)), "Unknown tree value")


def vector_field(node, field, size=3):
    value = node[field]
    require(isinstance(value, list) and len(value) == size and all(number(v) for v in value), "Invalid tree vector: " + field)


def validate_tree(tree, backend):
    finite_tree(tree)
    if backend == "gecko":
        require(isinstance(tree, dict) and set(tree) == {"roots", "nodes"}, "Missing Gecko tree")
        nodes, roots = tree["nodes"], tree["roots"]
        require(isinstance(nodes, dict) and bool(nodes) and isinstance(roots, list)
                and bool(roots) and len(set(roots)) == len(roots) and set(roots) <= set(nodes), "Invalid Gecko roots")
        required = {"parent", "position", "pivot", "rotationRadians", "scale", "hidden", "cubesHidden", "childrenHidden", "cubeCount", "children"}
        for name, node in nodes.items():
            require(isinstance(name, str) and set(node) == required, "Missing Gecko bind fields")
            for field in ("position", "pivot", "rotationRadians", "scale"):
                vector_field(node, field)
            require(all(type(node[key]) is bool for key in ("hidden", "cubesHidden", "childrenHidden")), "Invalid visibility")
            require(type(node["cubeCount"]) is int and node["cubeCount"] >= 0, "Invalid cube count")
            require(node["parent"] is None or node["parent"] in nodes, "Missing Gecko parent")
            require(isinstance(node["children"], list) and set(node["children"]) <= set(nodes)
                    and len(set(node["children"])) == len(node["children"]), "Invalid Gecko children")
        require(set(roots) == {name for name, node in nodes.items() if node["parent"] is None}, "Wrong Gecko root ownership")
        visited = set()

        def visit(name):
            require(name not in visited, "Repeated/cyclic Gecko node")
            visited.add(name)
            for child in nodes[name]["children"]:
                require(nodes[child]["parent"] == name, "Wrong Gecko child ownership")
                visit(child)

        for root in roots:
            visit(root)
        require(visited == set(nodes), "Unreachable Gecko nodes")
    else:
        require(isinstance(tree, list) and bool(tree), "Missing Bedrock tree")
        required = {"path", "name", "cubeCount", "childCount", "position", "offset", "rotationRadians", "scale", "additionalQuaternion", "visible", "illuminated", "mirror"}
        require(len({node["path"] for node in tree}) == len(tree), "Duplicate Bedrock paths")
        for node in tree:
            require(set(node) == required and isinstance(node["path"], str)
                    and (node["name"] is None or isinstance(node["name"], str)), "Missing Bedrock bind fields")
            for field in ("position", "offset", "rotationRadians", "scale"):
                vector_field(node, field)
            vector_field(node, "additionalQuaternion", 4)
            require(all(type(node[key]) is bool for key in ("visible", "illuminated", "mirror")), "Invalid Bedrock visibility")
            require(all(type(node[key]) is int and node[key] >= 0 for key in ("cubeCount", "childCount")), "Invalid Bedrock counts")


def validate_identity(report, reference):
    identity = report["identity"]
    build = identity["build"]
    expected_schema = "geometry-build-identity-v2" if reference else "port-geometry-build-v2"
    require(build["schema"] == expected_schema, "Unsupported definition manifest")
    require(build["contractSha256"] == CONTRACT_SHA, "Definition manifest has different contract")
    require(digest(identity["buildManifestSha256"]) and digest(build["agentSha256"]), "Missing identity digests")
    require(identity["runtimePolicy"] == ("geometry-v2-two-passive-observers" if reference else "geometry-v2-single-passive-observer"), "Wrong observer policy")
    compiled = build["compiledClasses"]
    actual = identity["actualFirstDefinitions"]
    required = REFERENCE_REQUIRED if reference else PORT_REQUIRED
    require(required <= set(compiled) and set(actual) == set(compiled), "Incomplete selected definitions")
    for name, expected in compiled.items():
        definition = actual[name]
        require(digest(expected) and digest(definition["sha256"]), "Missing compiled/definition digest: " + name)
        if reference:
            require(digest(definition["resourceSha256"]) and definition["resourceSha256"] == expected,
                    "Original class resource differs from compiled manifest: " + name)
        else:
            require(definition["sha256"] == expected, "Defined production bytes differ: " + name)
        require(type(definition["count"]) is int and definition["count"] == 1
                and definition["stage"] == STAGE and isinstance(definition["loaderClass"], str)
                and bool(definition["loaderClass"]), "Unreviewed definition: " + name)
    if reference:
        require(identity["baseReferenceRuntimeVerified"] is False and identity["baseReferenceBuildIdentityAnchored"] is True,
                "Geometry must not claim animation-v1 runtime identity")
        base = build["baseReferenceBuildIdentity"]
        require(identity["runtimeJava"] == base["java"], "Runtime Java differs from approved original build")
        require(build["upstreamCommit"] == CONTRACT["upstreamCommit"]
                and base["upstreamCommit"] == CONTRACT["upstreamCommit"], "Wrong original source")
        require(base["schema"] == "reference-build-identity-v1" and base["status"] == "LOCK_VERIFIED"
                and digest(build["baseReferenceBuildIdentitySha256"]), "Missing approved original build chain")
    else:
        validate_dependency_identity(identity, compiled, actual)


def validate_dependency_identity(identity, compiled, actual):
    build = identity["build"]
    require(identity["dependencyPolicy"] == FASTUTIL_POLICY and build["dependencyPolicy"] == FASTUTIL_POLICY,
            "Missing reviewed fastutil dependency policy")
    artifacts = build["externalArtifacts"]
    require(isinstance(artifacts, dict) and set(artifacts) == {FASTUTIL_COORDINATE}, "Missing/extra resolved dependency artifact")
    artifact = artifacts[FASTUTIL_COORDINATE]
    require(artifact["group"] == "it.unimi.dsi" and artifact["name"] == "fastutil" and artifact["version"] == "8.5.18",
            "Wrong resolved fastutil coordinates")
    require(digest(artifact["sha256"]) and isinstance(artifact["resolvedFile"], str) and bool(artifact["resolvedFile"])
            and isinstance(artifact["fileName"], str) and artifact["fileName"].endswith(".jar"), "Invalid resolved dependency identity")
    require(artifact["resolvedFile"].replace("\\", "/").rsplit("/", 1)[-1] == artifact["fileName"], "Resolved dependency filename differs")
    require(identity["actualExternalArtifacts"] == artifacts, "Actual dependency artifact differs from resolved manifest")
    external = build["externalClasses"]
    require(isinstance(external, dict) and set(external) == FASTUTIL_CLASSES, "Missing/extra external class entry identities")
    for name in FASTUTIL_CLASSES:
        expected, definition = external[name], actual[name]
        entry = name.replace(".", "/") + ".class"
        require(expected["artifact"] == FASTUTIL_COORDINATE and expected["entry"] == entry
                and digest(expected["sha256"]) and expected["sha256"] == compiled[name], "External expected bytes are not from declared JAR entry")
        require(definition["artifact"] == FASTUTIL_COORDINATE and definition["artifactSha256"] == artifact["sha256"],
                "Actual external class artifact differs")
        require(definition["resourceSha256"] == expected["sha256"] and definition["resourceEntry"] == entry,
                "Actual external class resource differs from selected JAR entry")
        require(definition["codeSourceFile"] == artifact["resolvedFile"]
                and definition["resourceArtifactFile"] == artifact["resolvedFile"], "External CodeSource/resource is not the resolved dependency JAR")


def semantic_key(value):
    return (value["bone"], value["cube"], value["face"], value["corner"])


def validate_trace(sample):
    require(sample["mapping"] == "actual-bone-cube-face-corner", "Missing actual bone mapping")
    nodes = sample["bindTree"]["nodes"]
    masks = sample["boneFaces"]
    require(isinstance(masks, dict) and set(masks) == set(nodes), "Actual bone callbacks do not cover bind tree")
    expected = set()
    local_order = {}
    for bone, node in nodes.items():
        require(node["hidden"] is False and node["cubesHidden"] is False and node["childrenHidden"] is False
                and node["scale"] == [1, 1, 1], "Unsupported bind visibility/scale")
        values = masks[bone]
        require(isinstance(values, list) and len(values) == node["cubeCount"], "Actual mesh cube coverage differs")
        local_order[bone] = []
        for cube, mask in enumerate(values):
            require(type(mask) is int and 0 <= mask <= 127, "Unknown actual mesh face mask")
            for face, name in enumerate(CONTRACT["faceOrder"]):
                if mask & (1 << face):
                    items = [(bone, cube, name, corner) for corner in range(4)]
                    expected.update(items)
                    local_order[bone].extend(items)
    keys = sample["vertexKeys"]
    require(isinstance(keys, list) and len(keys) == len(sample["vertices"]), "Key/vertex coverage differs")
    observed = set()
    actual_local = {bone: [] for bone in masks}
    completed_bones = set()
    current_bone = None
    for index, key in enumerate(keys):
        require(isinstance(key, dict) and set(key) == {"bone", "cube", "face", "corner", "drawIndex"}, "Incomplete semantic key")
        require(isinstance(key["bone"], str) and type(key["cube"]) is int and key["cube"] >= 0
                and isinstance(key["face"], str) and key["face"] in CONTRACT["faceOrder"]
                and type(key["corner"]) is int and 0 <= key["corner"] < 4
                and type(key["drawIndex"]) is int and key["drawIndex"] == index, "Invalid actual draw identity")
        identity = semantic_key(key)
        require(identity not in observed, "Duplicate semantic vertex key")
        require(key["bone"] in actual_local, "Vertex has no actual bone callback")
        if key["bone"] != current_bone:
            require(key["bone"] not in completed_bones, "Interleaved actual bone emissions")
            if current_bone is not None:
                completed_bones.add(current_bone)
            current_bone = key["bone"]
        actual_local[key["bone"]].append(identity)
        observed.add(identity)
    require(observed == expected, "Semantic face/corner coverage is incomplete or excessive")
    require(actual_local == local_order, "Actual cube/face/corner order within a bone differs from native emission contract")


def validate(report, reference):
    require(report["schema"] == ("geometry-reference-v2" if reference else "port-geometry-v2"), "Wrong geometry schema")
    require(report["contract"] == CONTRACT["contract"] and report["contractSha256"] == CONTRACT_SHA, "Wrong bind contract")
    require(report["coordinateContract"] == CONTRACT["coordinateContract"], "Undeclared coordinate conversion")
    require(report["coordinateSpace"] == ("upstream-native-bind" if reference else "port-modelbase-bind"), "Wrong coordinate space")
    model = report["model"]
    require(model in CONTRACT["models"] and report["backend"] == CONTRACT["models"][model]["backend"], "Wrong model/backend")
    require(CONTRACT["referenceGeometryNewlineContract"] == "canonical-lf-or-full-crlf-v1", "Unsupported reference JSON newline contract")
    variants = CONTRACT["models"][model]["referenceGeometrySha256"]
    require(set(variants) == {"LF", "CRLF"} and all(digest(value) for value in variants.values())
            and variants["LF"] == CONTRACT["models"][model]["geometrySha256"], "Invalid approved JSON newline variants")
    for key in ("geometrySha256", "textureSha256"):
        approved = CONTRACT["models"][model][key]
        require(digest(approved) and digest(report[key]), "Missing approved/actual asset identity: " + key)
        allowed = set(variants.values()) if reference and key == "geometrySha256" else {approved}
        require(report[key] in allowed, "Actual asset differs from approved fixed upstream: " + key)
    for key in ("worldRendererVerified", "gpuVerified", "dynamicAnimationVerified", "materialAttributesCompared", "orderDependentRenderingVerified"):
        require(report[key] is False, "Overclaimed geometry scope: " + key)
    validate_identity(report, reference)
    require(report["rawAAStable"] is True and report["observerNeutralityVerified"] is True,
            "Separate raw stability and observation neutrality evidence required")
    samples = report["samples"]
    require(isinstance(samples, list) and len(samples) == 3, "Three fresh builds required")
    for i, sample in enumerate(samples):
        require(type(sample["repetition"]) is int and sample["repetition"] == i
                and sample["freshNativeParse"] is True, "Not a fresh ordered bind parse")
        validate_tree(sample["bindTree"], report["backend"])
        vertices = sample["vertices"]
        require(isinstance(vertices, list) and 4 <= len(vertices) <= 50000 and len(vertices) % 4 == 0, "Missing/incomplete vertex stream")
        require(all(isinstance(row, list) and len(row) == 8 and all(number(v) for v in row) for row in vertices), "Invalid vertex values")
        require(sample["freshPlainParse"] is True and sample["observerNeutral"] is True
                and sample["plainVertices"] == vertices, "Fresh plain/traced raw outputs differ or are missing")
        if report["backend"] == "gecko":
            validate_trace(sample)
        else:
            require(sample["mapping"] == "ordered-bedrock" and "vertexKeys" not in sample and "boneFaces" not in sample,
                    "Bedrock must retain its ordered path")
        if reference:
            require(sample["nativeNormalBranch"] in ("transformed-edge-cross", "pose-normal-matrix"), "Unknown original normal branch")
    first = samples[0]
    for other in samples[1:]:
        require(first["bindTree"] == other["bindTree"] and first["vertices"] == other["vertices"], "A/A bind output is unstable")
        if reference:
            require(first["nativeNormalBranch"] == other["nativeNormalBranch"], "Original normal branch changed during A/A")
        if report["backend"] == "gecko":
            require(first["vertexKeys"] == other["vertexKeys"] and first["boneFaces"] == other["boneFaces"], "A/A actual bone mapping is unstable")


def first_difference(a, b, tolerance, path="tree"):
    if type(a) in (int, float) and type(b) in (int, float):
        return None if abs(a - b) <= tolerance else {"path": path, "expected": a, "actual": b}
    if type(a) is not type(b):
        return {"path": path, "reason": "type differs"}
    if isinstance(a, dict):
        if set(a) != set(b):
            return {"path": path, "reason": "keys differ"}
        for key in sorted(a):
            difference = first_difference(a[key], b[key], tolerance, path + "/" + key)
            if difference:
                return difference
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return {"path": path, "reason": "length differs", "expected": len(a), "actual": len(b)}
        for i, (x, y) in enumerate(zip(a, b)):
            difference = first_difference(x, y, tolerance, path + "/" + str(i))
            if difference:
                return difference
        return None
    return None if a == b else {"path": path, "expected": a, "actual": b}


def canonical_vertex(row, backend):
    # Declared basis only; never fit the reference to production output
    x, y, z, nx, ny, nz, u, v = row
    return [-x, 1.5 - y, z, -nx, -ny, nz, u, v] if backend == "gecko" else list(row)


def tree_geometry_view(tree):
    # Sort semantic node names only; never sort coordinates, cube indices or corners
    return {"roots": sorted(tree["roots"]), "nodes": {
        name: {key: sorted(value) if key == "children" else value for key, value in node.items()}
        for name, node in tree["nodes"].items()}}


def compare(reference, port):
    result = {"schema": "geometry-comparison-v2", "matched": False,
              "orderDependentRenderingVerified": False,
              "scope": "actual semantic Gecko faces/corners or ordered Bedrock values; static topology only, no draw-order/material/animation/world/GPU acceptance"}
    try:
        validate(reference, True)
        validate(port, False)
        result["rawAAStable"] = True
        result["observerNeutralityVerified"] = True
        require(reference["model"] == port["model"], "Different model")
        result["model"] = reference["model"]
        model = CONTRACT["models"][reference["model"]]
        result["assets"] = {
            "geometry": {"canonicalSha256": model["geometrySha256"],
                         "referenceActualSha256": reference["geometrySha256"],
                         "portActualSha256": port["geometrySha256"],
                         "referenceNewlines": next(name for name, value in model["referenceGeometrySha256"].items()
                                                   if value == reference["geometrySha256"])},
            "texture": {"canonicalSha256": model["textureSha256"],
                        "referenceActualSha256": reference["textureSha256"],
                        "portActualSha256": port["textureSha256"]}}
        a, b = reference["samples"][0], port["samples"][0]
        gecko = reference["backend"] == "gecko"
        at = tree_geometry_view(a["bindTree"]) if gecko else a["bindTree"]
        bt = tree_geometry_view(b["bindTree"]) if gecko else b["bindTree"]
        tree_diff = first_difference(at, bt, CONTRACT["absoluteTolerance"]["tree"])
        result["bindTreeMatched"] = tree_diff is None
        result["firstTreeDifference"] = tree_diff
        av, bv = a["vertices"], b["vertices"]
        result["vertexCount"] = {"reference": len(av), "port": len(bv)}
        if len(av) != len(bv):
            result["verdict"] = "GEOMETRY_DIFFERENCE"
            result["error"] = "Vertex count differs"
            return result
        if gecko:
            mesh_diff = first_difference(a["boneFaces"], b["boneFaces"], 0, "actualMeshFaces")
            result["firstMeshDifference"] = mesh_diff
            ak = [semantic_key(key) for key in a["vertexKeys"]]
            bk = [semantic_key(key) for key in b["vertexKeys"]]
            if mesh_diff is not None or set(ak) != set(bk):
                result["verdict"] = "GEOMETRY_DIFFERENCE"
                result["error"] = "Actual bone/cube/face identities differ"
                return result
            lookup = {key: index for index, key in enumerate(bk)}
            pairs = [(index, lookup[key], av[index], bv[lookup[key]], key) for index, key in enumerate(ak)]
            sibling_changes = []
            for name, node in a["bindTree"]["nodes"].items():
                other = b["bindTree"]["nodes"].get(name)
                if other is not None and node["children"] != other["children"]:
                    sibling_changes.append({"bone": name, "reference": node["children"], "port": other["children"]})
            result["drawOrder"] = {"matched": ak == bk, "firstDifference": next((
                {"drawIndex": i, "reference": list(x), "port": list(y)} for i, (x, y) in enumerate(zip(ak, bk)) if x != y), None),
                "siblingOrderDifferences": sibling_changes,
                "rootOrderMatched": a["bindTree"]["roots"] == b["bindTree"]["roots"],
                "orderDependentRenderingVerified": False}
        else:
            pairs = [(i, i, raw, actual, None) for i, (raw, actual) in enumerate(zip(av, bv))]
            result["drawOrder"] = {"scope": "ordered Bedrock stream; no semantic bone mapping", "orderDependentRenderingVerified": False}
        for label, columns in (("position", range(3)), ("normal", range(3, 6)), ("uv", range(6, 8))):
            maximum = 0.0
            first = None
            tolerance = CONTRACT["absoluteTolerance"][label]
            for index, port_index, raw, actual, key in pairs:
                expected = canonical_vertex(raw, reference["backend"])
                for column in columns:
                    error = abs(expected[column] - actual[column])
                    maximum = max(maximum, error)
                    if error > tolerance and first is None:
                        first = {"referenceDrawIndex": index, "portDrawIndex": port_index, "semanticKey": list(key) if key else None,
                                 "field": CONTRACT["vertexFields"][column], "expected": expected[column], "actual": actual[column], "error": error}
            result[label] = {"matched": first is None, "maximumAbsoluteError": maximum, "tolerance": tolerance, "firstDifference": first}
        result["matched"] = result["bindTreeMatched"] and all(result[k]["matched"] for k in ("position", "normal", "uv"))
        result["verdict"] = "BOUNDED_BIND_GEOMETRY_V2_MATCH" if result["matched"] else "GEOMETRY_DIFFERENCE"
    except (ValueError, KeyError, TypeError, IndexError) as failure:
        result["verdict"] = "INCONCLUSIVE"
        result["error"] = str(failure)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("port", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    original = args.reference.read_bytes()
    replay = json.loads(args.port.read_text(encoding="utf-8"))
    require(replay["referenceSha256"] == hashlib.sha256(original).hexdigest(), "Port capture is bound to a different original file")
    result = compare(json.loads(original), replay)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["matched"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
