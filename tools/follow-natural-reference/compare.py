"""检查真实自然采集的证据与差异，只报告可行性和描述性变异，不给AI测评PASS"""

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path

TOOL = Path(__file__).resolve().parent
BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
MAX_JSON = 67_108_864
if not __debug__:
    raise RuntimeError("Do not disable evidence verification with Python optimization")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result


def finite_values(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Non-finite JSON number")
    if isinstance(value, dict):
        for item in value.values():
            finite_values(item)
    if isinstance(value, list):
        for item in value:
            finite_values(item)


def load(path):
    with Path(path).open("rb") as stream:
        compressed = stream.read(2) == b"\x1f\x8b"
    with (gzip.open(path, "rb") if compressed else Path(path).open("rb")) as stream:
        raw = stream.read(MAX_JSON + 1)
    if len(raw) > MAX_JSON:
        raise ValueError("Bounded natural JSON size exceeded")
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                       parse_constant=lambda name: (_ for _ in ()).throw(ValueError(name)))
    finite_values(value)
    return value


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def first_difference(left, right, path="$"):
    if isinstance(left, bool) != isinstance(right, bool):
        return {"path": path, "first": left, "other": right}
    if isinstance(left, dict) and isinstance(right, dict):
        if left.keys() != right.keys():
            return {"path": path, "firstKeys": sorted(left), "otherKeys": sorted(right)}
        for key in left:
            result = first_difference(left[key], right[key], path + "." + key)
            if result:
                return result
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return {"path": path, "firstLength": len(left), "otherLength": len(right)}
        for index, (a, b) in enumerate(zip(left, right)):
            result = first_difference(a, b, f"{path}[{index}]")
            if result:
                return result
    elif left != right:
        return {"path": path, "first": left, "other": right}
    return None


def identity(capture, manifest_path):
    build = load(manifest_path)
    selected = load(TOOL / "identity-classes.json")
    runtime = capture["identity"]
    assert build["schema"] == "follow-natural-reference-build-v1" and build["status"] == "BASE_LOCK_VERIFIED"
    assert build["upstreamCommit"] == runtime["upstreamCommit"] == BASE
    assert build["minecraft"] == "1.20.1" and build["forge"] == "47.2.0" and build["javaFeature"] == 17
    assert build["contractSha256"] == capture["contractSha256"] == sha(TOOL / "contract.json")
    assert build["identityClassesSha256"] == sha(TOOL / "identity-classes.json")
    assert runtime["schema"] == "follow-natural-reference-runtime-v1"
    assert runtime["status"] == "VERIFIED_SELECTED_NATURAL_CLASSES"
    assert runtime["buildIdentitySha256"] == sha(manifest_path)
    assert runtime["agentSha256"] == build["definitionAgentSha256"]
    assert runtime["baseReferenceBuildIdentitySha256"] == build["baseReferenceBuildIdentitySha256"]
    assert build["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED"
    assert set(runtime["classes"]) == set(selected["classes"]) == set(build["expectedResources"])
    for name, value in runtime["classes"].items():
        assert value["definitionCount"] == 1 and len(value["definitionSha256"]) == 64
        assert value["resourceSha256"] == build["expectedResources"][name]["resourceSha256"]
    for item in build["toolInputs"]:
        relative = Path(item["file"])
        assert not relative.is_absolute() and ".." not in relative.parts
        target = (TOOL / relative).resolve()
        assert target.is_relative_to(TOOL.resolve()) and sha(target) == item["sha256"]


def planned(case, index, origin):
    keys = case["ownerKeyframes"]
    previous = keys[0]
    for next_key in keys[1:]:
        if index <= next_key["frame"]:
            fraction = max(0, min(1, (index - previous["frame"]) / (next_key["frame"] - previous["frame"])))
            return [offset + a + (b - a) * fraction for offset, a, b in zip(origin, previous["position"], next_key["position"])]
        previous = next_key
    return [offset + value for offset, value in zip(origin, previous["position"])]


def check_empty_hands(owner, selected):
    assert owner["selectedSlot"] == selected and type(selected) is int and 0 <= selected < 9
    for key in ("mainHand", "offHand"):
        hand = owner[key]
        assert hand["empty"] is True and hand["count"] == 0 and hand["registryId"] == "minecraft:air"


def check_snapshot(snapshot, row):
    assert snapshot["maid"]["class"] == "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid"
    assert snapshot["maid"]["uuid"] == row["maidUuid"] and snapshot["owner"]["uuid"] == row["ownerUuid"]
    assert snapshot["maid"]["alive"] and not snapshot["maid"]["removed"] and snapshot["noAi"] is False
    assert snapshot["guiOpening"] is False
    assert snapshot["worldRegisteredByUuid"] and snapshot["worldRegisteredById"] and snapshot["positionEntityTicking"]
    assert snapshot["ownerResolvedSamePlayerListObject"] and snapshot["ownerConnection"]["connected"]
    assert snapshot["owner"]["mode"] == "creative" and snapshot["owner"]["spectator"] is False and snapshot["owner"]["abilityInvulnerable"]
    check_empty_hands(snapshot["owner"], row["samplingSelectedSlot"])
    assert snapshot["task"] == "touhou_little_maid:idle" and snapshot["schedule"] == "ALL" and snapshot["radius"] == 8
    assert snapshot["home"] is False and snapshot["sleeping"] is False and snapshot["sitting"] is False
    for module in ("minecraft:walk_target", "minecraft:path", "minecraft:cant_reach_walk_target_since"):
        assert module in snapshot["memory"]
    path = snapshot["navigation"]["path"]
    if path is not None:
        assert path["kind"] == "path" and path["nodeCount"] == len(path["nodes"])
        assert 0 <= path["nextNodeIndex"] <= path["nodeCount"] <= 256
    for key in ("movementAttribute", "followRangeAttribute"):
        assert {"base", "value", "modifiers"} == snapshot[key].keys()


def verified_rows(capture, manifest_path):
    contract = load(TOOL / "contract.json")
    assert capture["schema"] == "follow-natural-reference-v1" and capture["contract"] == "FOLLOW_NATURAL_O_V1"
    assert capture["complete"] and capture["cleanupComplete"] and capture["remainingActorRegistered"] is False
    assert capture["worldNatural"] and capture["completeOriginalBrain"] and capture["realLoggedInOwner"]
    assert capture["portEquivalenceVerified"] is False and capture["statisticalAdmission"] is False
    assert capture["worldRngReset"] is False and capture["worldRngReplaced"] is False and capture["maidRngReset"] is False
    assert capture["randomStateFullyControlled"] is False
    assert capture["transientBehaviorCallsObserved"] is False and capture["teleportMethodCallsObserved"] is False
    assert capture["ownerRestoration"]["serverStateVerified"] and capture["ownerAfterCleanup"]["spectator"]
    assert capture["ownerAfterCleanup"]["abilityInvulnerable"]
    assert first_difference(capture["ownerBefore"], capture["ownerAfterCleanupState"]) is None, "Actual owner state was not fully restored"
    assert capture["ownerAfterCleanup"]["position"] == capture["ownerAfterCleanupState"]["position"]
    original = capture["ownerBefore"]
    preparation = capture["slotPreparation"]
    assert len(original["hotbar"]) == 9 and [slot["slot"] for slot in original["hotbar"]] == list(range(9))
    assert original["selectedSlot"] == preparation["originalSelectedSlot"] == preparation["actualBefore"]["selectedSlot"]
    assert original["mainHand"] == preparation["actualBefore"]["mainHand"] and original["offHand"] == preparation["actualBefore"]["offHand"]
    sampling_slot = preparation["samplingSelectedSlot"]
    check_empty_hands(preparation["actualAfter"], sampling_slot)
    assert original["hotbar"][sampling_slot]["stack"]["empty"] is True
    assert preparation["syncPacket"] == "net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket"
    for hand in [original["mainHand"], original["offHand"], *(item["stack"] for item in original["hotbar"])]:
        assert len(hand["nbtSha256"]) == 64 and all(char in "0123456789abcdef" for char in hand["nbtSha256"])
    assert capture["ownerAfterCleanup"]["selectedSlot"] == original["selectedSlot"]
    assert capture["ownerAfterCleanup"]["mainHand"] == original["mainHand"] and capture["ownerAfterCleanup"]["offHand"] == original["offHand"]
    identity(capture, manifest_path)
    platform = capture["platform"]
    assert platform["creationComplete"] and platform["cleanupComplete"] and platform["radius"] == 32
    assert platform["preflightAirCells"] == 65 * 65 * 6
    assert platform["placedBlocks"] == platform["restoredBlocks"] == 65 * 65
    assert platform["forcedChunksAdded"] is False
    specs = {case["id"]: case for case in contract["cases"]}
    rows = {}
    actors = set()
    owners = set()
    for row in capture["runs"]:
        key = row["id"], row["rebuild"]
        assert key not in rows and row["complete"] and row["cleanupComplete"]
        assert row["maidUuid"] not in actors
        actors.add(row["maidUuid"])
        owners.add(row["ownerUuid"])
        spec = specs[row["id"]]
        assert row["input"] == spec and len(row["frames"]) == spec["ticks"]
        assert row["worldRngAndSensorPhaseUncontrolled"]
        assert row["samplingSelectedSlot"] == sampling_slot
        assert row["brainRegistration"]["originalActivitiesRemoved"] is False and row["brainRegistration"]["guiOpening"] is False
        registered = row["brainRegistration"]["behaviors"]
        assert any(value["priority"] == 2 and value["class"] == "net.minecraft.world.entity.ai.behavior.MoveToTargetSink" for value in registered)
        assert any(value["priority"] == 3 and value["class"].endswith(".MaidFollowOwnerTask") for value in registered)
        for index, frame in enumerate(row["frames"]):
            assert frame["index"] == index and frame["livingTickEvents"] == 1
            a, b = frame["before"], frame["after"]
            check_snapshot(a, row)
            check_snapshot(b, row)
            assert b["maid"]["tickCount"] == a["maid"]["tickCount"] + 1 and b["gameTime"] == a["gameTime"] + 1
            intended = planned(spec, index, contract["origin"])
            recorded = frame["ownerInput"]["plannedPosition"]
            check_empty_hands(frame["ownerInput"]["before"], sampling_slot)
            check_empty_hands(frame["ownerInput"]["actualAfterCall"], sampling_slot)
            assert max(abs(x - y) for x, y in zip(recorded, intended)) < 1e-12
            if frame["ownerInput"]["methodInvoked"]:
                assert frame["ownerInput"]["actualArguments"] == recorded
            for actual in (frame["ownerInput"]["actualAfterCall"]["position"], a["owner"]["position"], b["owner"]["position"]):
                assert sum((x - y) ** 2 for x, y in zip(actual, intended)) <= contract["ownerTolerance"] ** 2
            if index:
                previous = row["frames"][index - 1]["after"]
                assert a["maid"]["tickCount"] == previous["maid"]["tickCount"] and a["gameTime"] == previous["gameTime"]
        rows[key] = row
    assert set(rows) == {(name, rebuild) for name in specs for rebuild in range(3)} and len(owners) == 1
    return contract, rows


def normalise(snapshot, row, origin, base_time):
    """Only coordinates, known participant identities and their clocks; never normalise motion or attributes"""
    maid_uuid, owner_uuid = row["maidUuid"], row["ownerUuid"]
    initial = row["spawnState"]

    def walk(value, key=None):
        if isinstance(value, dict):
            entity_uuid = value.get("uuid")
            result = {k: walk(v, k) for k, v in value.items() if k != "ownerConnection"}
            if entity_uuid in (maid_uuid, owner_uuid):
                role = "maid" if entity_uuid == maid_uuid else "owner"
                result["uuid"] = role
                if "entityId" in result:
                    result["entityId"] = role
                if "tickCount" in result:
                    result["tickCount"] -= initial[role]["tickCount"]
            if result.get("kind") == "set":
                result["items"].sort(key=lambda x: json.dumps(x, sort_keys=True))
            return result
        if isinstance(value, list):
            if key in ("position", "block", "target", "wanted") and len(value) == 3 and all(isinstance(x, (int, float)) for x in value):
                return [x - offset for x, offset in zip(value, origin)]
            if key == "nodes":
                return [[x - offset for x, offset in zip(node, origin)] for node in value]
            return [walk(item) for item in value]
        if key == "gameTime":
            return value - base_time
        return value

    result = walk(snapshot)
    memory = result.get("memory", {}).get("minecraft:cant_reach_walk_target_since", {})
    if memory.get("present"):
        memory["value"] -= base_time
    return result


def describe(row):
    after = [frame["after"] for frame in row["frames"]]
    distances = [math.dist(item["maid"]["position"], item["owner"]["position"]) for item in after]
    steps = [math.dist(frame["before"]["maid"]["position"], frame["after"]["maid"]["position"]) for frame in row["frames"]]
    return {"id": row["id"], "rebuild": row["rebuild"], "frames": len(after),
            "distanceMinimum": min(distances), "distanceMaximum": max(distances), "distanceFinal": distances[-1],
            "largestObservedDisplacement": max(steps), "nativePathPresentFrames": sum(item["navigation"]["path"] is not None for item in after),
            "movementAttributeAtSpawn": row["spawnState"]["movementAttribute"],
            "initialSensorPhases": row["spawnState"]["sensors"], "teleportCauseDirectlyObserved": False}


def compare(capture_path, manifest_path):
    capture = load(capture_path)
    contract, rows = verified_rows(capture, manifest_path)
    result = {"schema": "follow-natural-variance-v1", "observationValid": True, "aiBenchmarkVerdict": "NOT_EVALUATED",
              "strictTrajectoryBaselineAdmitted": False, "statisticalAdmission": False,
              "sampleSha256": sha(capture_path), "contractSha256": capture["contractSha256"],
              "scope": "three fresh original world-tick runs per scenario; uncontrolled world/sensor randomness; descriptive variance only",
              "descriptiveRuns": [describe(row) for row in rows.values()], "comparisons": []}
    for spec in contract["cases"]:
        case_id = spec["id"]
        first = rows[case_id, 0]
        first_base = first["frames"][0]["before"]["gameTime"]
        a = [normalise(frame["after"], first, contract["origin"], first_base) for frame in first["frames"]]
        for rebuild in (1, 2):
            other = rows[case_id, rebuild]
            other_base = other["frames"][0]["before"]["gameTime"]
            b = [normalise(frame["after"], other, contract["origin"], other_base) for frame in other["frames"]]
            diff = first_difference(a, b)
            trajectory_a = [item["maid"] for item in a]
            trajectory_b = [item["maid"] for item in b]
            result["comparisons"].append({"id": case_id, "rebuild": rebuild,
                                           "exactObservedStateEqual": diff is None, "firstObservedDifference": diff,
                                           "exactMaidTrajectoryEqual": first_difference(trajectory_a, trajectory_b) is None})
    result["varianceObserved"] = any(not row["exactObservedStateEqual"] for row in result["comparisons"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--build-identity", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = compare(args.capture, args.build_identity)
    except (AssertionError, KeyError, ValueError, TypeError, OSError) as failure:
        result = {"schema": "follow-natural-variance-v1", "observationValid": False,
                  "aiBenchmarkVerdict": "NOT_EVALUATED", "errorType": type(failure).__name__, "error": str(failure)}
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(text)
    print(text, end="")
    return 0 if result["observationValid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
