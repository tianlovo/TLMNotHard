"""Synthetic protocol tests, not original method samples or an oracle."""

import copy
import tempfile
import unittest
from pathlib import Path

import compare


def fixture(callback=False):
    initial = {"maidBlock": [0, 192, 0], "targetBlock": [8, 192, 0], "targetSpectator": False,
               "walk": {"present": callback, "tracker": "entity", "speed": .5, "closeEnough": 2},
               "registered": {"walk": True, "path": True, "cantReachSince": True},
               "pathMemory": None, "cantReachSince": None,
               "navigation": {"path": None, "done": True, "stuck": False, "moveResult": True},
               "stableDestination": True, "malus": 0, "scoreMode": "constant", "onCreateMaidBlock": None}
    path = {"canReach": True, "target": [8, 192, 0], "nodes": [[0, 192, 0], [8, 192, 0]]}
    walk = {"block": [8, 192, 0], "speed": .5, "closeEnough": 2, "entityTrackerSpectator": False} if callback else None
    state = {"memory": {"registered": copy.deepcopy(initial["registered"]), "walk": walk, "path": None, "cantReachSince": None},
             "sink": {"status": "STOPPED", "remainingCooldown": 0, "path": None, "lastTargetPos": None, "speedModifier": 0, "endTimestamp": 0},
             "navigation": {"path": None, "done": True, "stuck": False}}

    def observation(world, maid_x, present):
        tracker = ({"type": "entity", "block": [8, 192, 0], "currentPosition": [8.5, 193.5, .5],
                    "entityIsConfiguredTarget": True, "entityUuid": "00000000-0000-0000-0000-000000000001",
                    "entityRuntimeClass": "synthetic.Target", "entitySpectator": False} if present else None)
        return {"maidBlock": [maid_x, 192, 0], "maidPosition": [maid_x + .5, 192, .5],
                "targetEntityBlock": [8, 192, 0], "targetEntityPosition": [8.5, 192, .5],
                "targetSpectator": False, "worldGameTime": world, "walkTracker": tracker}

    patch = {"createReplies": ["A"], "onCreateMaidBlock": [8, 192, 0]} if callback else {}
    planned = {"operation": "tryStart" if callback else "doStop", "methodTime": 100, "worldGameTime": 600, "patch": patch}
    spec = {"id": "synthetic-callback" if callback else "synthetic-noop", "initial": initial,
            "paths": {"A": path}, "worldInts": [], "maidSeed": 1, "steps": [planned]}
    after = copy.deepcopy(state)
    if callback:
        after["memory"]["walk"] = None
        after["sink"].update(path="A", speedModifier=.5)
    step = {"index": 0, "operation": planned["operation"], "methodTime": 100, "worldGameTime": 600,
            "input": {"externalPatch": copy.deepcopy(patch)}, "before": copy.deepcopy(state), "after": after,
            "methodInvocation": {"operation": planned["operation"], "methodTime": 100, "worldGameTime": 600,
                                 "entityIsFixtureMaid": True, "worldIsFixtureLevel": True},
            "inputBeforeInvocation": observation(600, 0, callback), "inputAfterInvocation": observation(600, 8 if callback else 0, False),
            "callTrace": [{"op": "createPath", "entry": "block", "target": [8, 192, 0], "accuracy": 0, "resultPath": "A"}] if callback else [{"op": "stopNavigation"}],
            "callbackObservations": [{"createIndex": 0, "from": [0, 192, 0], "to": [8, 192, 0], "actualAfterBlock": [8, 192, 0]}] if callback else [],
            "moveObservations": [], "fallbackObservation": {"stabilityChecks": [], "malusChecks": [], "scores": []}}
    row = {"id": spec["id"], "rebuild": 0, "input": copy.deepcopy(spec), "paths": copy.deepcopy(spec["paths"]),
           "initialState": state, "initialMaidBlock": [0, 192, 0], "initialTargetBlock": [8, 192, 0], "initialTargetSpectator": False,
           "initialInputObservation": observation(9000, 0, callback), "steps": [step]}
    return {"inputObservationSchema": compare.INPUT_SCHEMA, "cases": [row]}, {"cases": [spec]}


class InputBindingTest(unittest.TestCase):
    def test_actual_inputs_bind_without_computing_sink_outputs(self):
        for callback in (False, True):
            reference, contract = fixture(callback)
            self.assertTrue(compare.verify_input_binding(reference, contract)["verified"])

    def test_old_or_missing_actual_observations_cannot_be_admitted(self):
        for missing in ("inputObservationSchema",):
            reference, contract = fixture()
            del reference[missing]
            with self.assertRaises((KeyError, AssertionError)):
                compare.verify_input_binding(reference, contract)
        reference, contract = fixture()
        del reference["cases"][0]["steps"][0]["methodInvocation"]
        with self.assertRaises(KeyError):
            compare.verify_input_binding(reference, contract)

    def test_copied_declaration_does_not_hide_wrong_actual_initial_input(self):
        reference, contract = fixture()
        reference["cases"][0]["initialInputObservation"]["targetEntityBlock"] = [4, 192, 0]
        with self.assertRaises(AssertionError):
            compare.verify_input_binding(reference, contract)
        reference, contract = fixture()
        reference["cases"][0]["paths"]["A"]["canReach"] = False
        with self.assertRaises(AssertionError):
            compare.verify_input_binding(reference, contract)

    def test_planned_patch_must_really_appear_before_invocation(self):
        reference, contract = fixture()
        contract["cases"][0]["steps"][0]["patch"] = {"navigation": {"done": False}}
        reference["cases"][0]["input"] = copy.deepcopy(contract["cases"][0])
        reference["cases"][0]["steps"][0]["input"]["externalPatch"] = {"navigation": {"done": False}}
        with self.assertRaises(AssertionError):
            compare.verify_input_binding(reference, contract)

    def test_actual_invocation_arguments_must_match_plan(self):
        for key, wrong in (("methodTime", 0), ("worldGameTime", 601), ("entityIsFixtureMaid", False), ("worldIsFixtureLevel", False)):
            reference, contract = fixture()
            reference["cases"][0]["steps"][0]["methodInvocation"][key] = wrong
            with self.assertRaises(AssertionError):
                compare.verify_input_binding(reference, contract)

    def test_callback_label_cannot_replace_actual_after_position(self):
        reference, contract = fixture(True)
        reference["cases"][0]["steps"][0]["callbackObservations"][0]["actualAfterBlock"] = [0, 192, 0]
        with self.assertRaises(AssertionError):
            compare.verify_input_binding(reference, contract)
        reference, contract = fixture(True)
        del reference["cases"][0]["steps"][0]["callbackObservations"][0]["actualAfterBlock"]
        with self.assertRaises(KeyError):
            compare.verify_input_binding(reference, contract)

    def test_unpatched_internal_state_must_be_continuous_between_steps(self):
        reference, contract = fixture()
        row = reference["cases"][0]
        next_step = copy.deepcopy(row["steps"][0])
        next_step.update(index=1, methodTime=101, worldGameTime=601)
        next_step["methodInvocation"].update(methodTime=101, worldGameTime=601)
        next_step["inputBeforeInvocation"]["worldGameTime"] = 601
        next_step["inputAfterInvocation"]["worldGameTime"] = 601
        next_step["before"]["sink"]["endTimestamp"] = 777
        row["steps"].append(next_step)
        contract["cases"][0]["steps"].append({"operation": "doStop", "methodTime": 101, "worldGameTime": 601, "patch": {}})
        with self.assertRaises(AssertionError):
            compare.verify_input_binding(reference, contract)

    def test_loader_rejects_duplicate_keys_and_nonfinite_numbers(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            for text in ('{"x":1,"x":2}', '{"x":1e400}', '{"x":NaN}'):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    compare.load(path)


if __name__ == "__main__":
    unittest.main()
