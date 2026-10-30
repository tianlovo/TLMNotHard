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
REFERENCE_REQUIRED = {
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoMesh",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree",
    "com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubePerFace",
    "com.tianluoqaq.tlmnh.geometry.GeometrySnapshots",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe",
    "com.tianluoqaq.tlmnh.reference.GeometryReferenceProbe$Sink",
}
PORT_REQUIRED = {
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels",
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel",
    "com.tianluoqaq.tlmnh.client.render.LegacyPoseStack",
    "com.tianluoqaq.tlmnh.client.render.BedrockPose",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoMesh",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox",
    "com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubePerFace",
    "com.tianluoqaq.tlmnh.geometry.GeometrySnapshots",
    "com.tianluoqaq.tlmnh.geometry.replay.GeometryPortReplay",
    "com.tianluoqaq.tlmnh.geometry.replay.GeometryPortReplay$Sink",
}


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
    expected_schema = "geometry-build-identity-v1" if reference else "port-geometry-build-v1"
    require(build["schema"] == expected_schema, "Unsupported definition manifest")
    require(build["contractSha256"] == CONTRACT_SHA, "Definition manifest has different contract")
    require(digest(identity["buildManifestSha256"]) and digest(build["agentSha256"]), "Missing identity digests")
    require(identity["runtimePolicy"] == ("geometry-v1-two-passive-observers" if reference else "geometry-v1-single-passive-observer"), "Wrong observer policy")
    compiled = build["compiledClasses"]
    actual = identity["actualFirstDefinitions"]
    required = REFERENCE_REQUIRED if reference else PORT_REQUIRED
    require(required <= set(compiled) and set(actual) == set(compiled), "Incomplete selected definitions")
    for name, expected in compiled.items():
        definition = actual[name]
        require(digest(expected) and definition["sha256"] == expected, "Defined bytes differ: " + name)
        require(type(definition["count"]) is int and definition["count"] == 1
                and definition["stage"] == STAGE and bool(definition["loaderClass"]), "Unreviewed definition: " + name)
    if reference:
        require(identity["baseReferenceRuntimeVerified"] is False and identity["baseReferenceBuildIdentityAnchored"] is True,
                "Geometry must not claim animation-v1 runtime identity")
        base = build["baseReferenceBuildIdentity"]
        require(identity["runtimeJava"] == base["java"], "Runtime Java differs from approved original build")
        require(build["upstreamCommit"] == CONTRACT["upstreamCommit"]
                and base["upstreamCommit"] == CONTRACT["upstreamCommit"], "Wrong original source")
        require(base["schema"] == "reference-build-identity-v1" and base["status"] == "LOCK_VERIFIED"
                and digest(build["baseReferenceBuildIdentitySha256"]), "Missing approved original build chain")


def validate(report, reference):
    require(report["schema"] == ("geometry-reference-v1" if reference else "port-geometry-v1"), "Wrong geometry schema")
    require(report["contract"] == CONTRACT["contract"] and report["contractSha256"] == CONTRACT_SHA, "Wrong bind contract")
    require(report["coordinateContract"] == CONTRACT["coordinateContract"], "Undeclared coordinate conversion")
    require(report["coordinateSpace"] == ("upstream-native-bind" if reference else "port-modelbase-bind"), "Wrong coordinate space")
    model = report["model"]
    require(model in CONTRACT["models"] and report["backend"] == CONTRACT["models"][model]["backend"], "Wrong model/backend")
    for key in ("geometrySha256", "textureSha256"):
        approved = CONTRACT["models"][model][key]
        require(digest(approved) and digest(report[key]), "Missing approved/actual asset identity: " + key)
        require(report[key] == approved, "Actual asset differs from approved fixed upstream: " + key)
    for key in ("worldRendererVerified", "gpuVerified", "dynamicAnimationVerified", "materialAttributesCompared"):
        require(report[key] is False, "Overclaimed geometry scope: " + key)
    validate_identity(report, reference)
    samples = report["samples"]
    require(isinstance(samples, list) and len(samples) == 3, "Three fresh builds required")
    for i, sample in enumerate(samples):
        require(type(sample["repetition"]) is int and sample["repetition"] == i
                and sample["freshNativeParse"] is True, "Not a fresh ordered bind parse")
        validate_tree(sample["bindTree"], report["backend"])
        vertices = sample["vertices"]
        require(isinstance(vertices, list) and 4 <= len(vertices) <= 50000 and len(vertices) % 4 == 0, "Missing/incomplete vertex stream")
        require(all(isinstance(row, list) and len(row) == 8 and all(number(v) for v in row) for row in vertices), "Invalid vertex values")
        if reference:
            require(sample["nativeNormalBranch"] in ("transformed-edge-cross", "pose-normal-matrix"), "Unknown original normal branch")
    first = samples[0]
    for other in samples[1:]:
        require(first["bindTree"] == other["bindTree"] and first["vertices"] == other["vertices"], "A/A bind output is unstable")
        if reference:
            require(first["nativeNormalBranch"] == other["nativeNormalBranch"], "Original normal branch changed during A/A")


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


def compare(reference, port):
    result = {"schema": "geometry-comparison-v1", "matched": False,
              "scope": "two reviewed bind meshes, ordered positions/normals/UV and static tree; no animation/world/GPU acceptance"}
    try:
        validate(reference, True)
        validate(port, False)
        require(reference["model"] == port["model"], "Different model")
        for key in ("geometrySha256", "textureSha256"):
            require(reference[key] == port[key], "Different actual asset: " + key)
        result["model"] = reference["model"]
        a, b = reference["samples"][0], port["samples"][0]
        tree_diff = first_difference(a["bindTree"], b["bindTree"], CONTRACT["absoluteTolerance"]["tree"])
        result["bindTreeMatched"] = tree_diff is None
        result["firstTreeDifference"] = tree_diff
        av, bv = a["vertices"], b["vertices"]
        result["vertexCount"] = {"reference": len(av), "port": len(bv)}
        if len(av) != len(bv):
            result["verdict"] = "GEOMETRY_DIFFERENCE"
            result["error"] = "Vertex count differs"
            return result
        for label, columns in (("position", range(3)), ("normal", range(3, 6)), ("uv", range(6, 8))):
            maximum = 0.0
            first = None
            tolerance = CONTRACT["absoluteTolerance"][label]
            for index, (raw, actual) in enumerate(zip(av, bv)):
                expected = canonical_vertex(raw, reference["backend"])
                for column in columns:
                    error = abs(expected[column] - actual[column])
                    maximum = max(maximum, error)
                    if error > tolerance and first is None:
                        first = {"vertex": index, "field": CONTRACT["vertexFields"][column], "expected": expected[column], "actual": actual[column], "error": error}
            result[label] = {"matched": first is None, "maximumAbsoluteError": maximum, "tolerance": tolerance, "firstDifference": first}
        result["matched"] = result["bindTreeMatched"] and all(result[k]["matched"] for k in ("position", "normal", "uv"))
        result["verdict"] = "BOUNDED_BIND_GEOMETRY_MATCH" if result["matched"] else "GEOMETRY_DIFFERENCE"
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
