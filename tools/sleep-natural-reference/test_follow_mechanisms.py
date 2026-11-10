"""跟随与探索机制检查器的离线反例单元测试。"""

import copy
import unittest
import xml.etree.ElementTree as ET

from compare_follow_mechanisms import check_captured_contract, check_event_schedule, layout, metrics, owner_facts, verify_port_results


def captured_owner(profile):
    return {"class": "net.minecraft.server.level.ServerPlayer" if profile == "original" else "net.minecraft.entity.player.EntityPlayerMP",
            "realNetworkLogin": False, "worldRegistered": True, "worldPlayerList": True,
            "nativeHandlerPresent": True, "memoryChannelOpen": True,
            "connectedPlayerRegistry": True, "uuidOwnerLookup": profile == "original",
            "alive": True, "spectator": False, "sneaking": False, "invisible": False,
            "mode": "creative", "flying": True, "mayFly": True, "disableDamage": True,
            "position": [9, 1, 0], "eyePosition": [9, 2.62, 0], "eyeHeight": 1.62, "width": .6, "height": 1.8}


def observations(mechanism="temporary_obstacle"):
    frames = [{"frame": i, "position": [0, 1, 0], "owner": {"position": [9, 1, 0]},
               "walkTarget": False, "sitting": True, "food": {"count": 1}} for i in range(3)]
    return {"mechanism": mechanism, "contract": {"arrivalDistance": 2}, "actualInputs": {"position": [0, 1, 0]},
            "frames": frames, "events": [{"frame": 0 if mechanism != "waiting_owner_food" else 1}]}


class FollowMechanismCounterexamples(unittest.TestCase):

    def test_no_arrival_is_retained_instead_of_becoming_success_or_dropped(self):
        data = observations(); before = copy.deepcopy(data); result = metrics(data)
        self.assertFalse(result["arrival_observed"])
        self.assertIsNone(result["ticks_after_last_event_to_arrival"])
        self.assertEqual(result["all_frames_retained"], 3)
        self.assertEqual(result["task_success_and_noninferiority_verdict"], "NOT_EVALUATED")
        self.assertEqual(data, before)

    def test_recovery_uses_observed_owner_after_reversal_not_planned_future_owner(self):
        data = observations("owner_reversal")
        data["frames"][1]["owner"]["position"] = [1, 1, 0]
        data["frames"][1]["ownerInput"] = {"plannedPosition": [9, 1, 0]}
        result = metrics(data)
        self.assertEqual(result["first_arrival_frame_after_last_event"], 1)
        self.assertEqual(result["ticks_after_last_event_to_arrival"], 1)

    def test_waiting_movement_walk_target_and_food_consumption_remain_visible(self):
        data = observations("waiting_owner_food")
        data["frames"][1].update(position=[1, 1, 0], sitting=False, walkTarget=True, food={"count": 0})
        result = metrics(data)["waiting_before_release"]
        self.assertEqual(result["maximum_horizontal_displacement"], 1)
        self.assertEqual(result["sitting_false_frames"], 1)
        self.assertEqual(result["walk_target_frames"], 1)
        self.assertEqual(result["food_remaining_count_at_release"], 0)
        self.assertEqual(result["compliance_verdict"], "NOT_EVALUATED")

    def test_real_native_class_and_actual_owner_registration_cannot_be_fabricated(self):
        for profile in ("original", "enhanced"):
            owner = captured_owner(profile); owner_facts(owner, profile)
            for field, value in (("class", "net.minecraftforge.common.util.FakePlayer"),
                                 ("realNetworkLogin", True), ("nativeHandlerPresent", False)):
                with self.subTest(profile=profile, field=field), self.assertRaises(ValueError):
                    owner_facts(dict(owner, **{field: value}), profile)
        with self.assertRaisesRegex(ValueError, "PlayerList UUID"):
            owner_facts(dict(captured_owner("original"), connectedPlayerRegistry=False, uuidOwnerLookup=False), "original")

    def test_wrong_event_phase_or_missing_dynamic_event_is_rejected(self):
        scene = {"events": [{"frame": 10, "kind": "close_obstacle"}, {"frame": 70, "kind": "open_obstacle"}]}
        events = [dict(value, phase="END_after_recorded_frame") for value in scene["events"]]
        check_event_schedule(scene, events)
        with self.assertRaises(ValueError): check_event_schedule(scene, events[:1])
        events[0]["phase"] = "START_before_world"
        with self.assertRaises(ValueError): check_event_schedule(scene, events)

    def test_unknown_block_metadata_and_duplicate_cells_are_not_ignored(self):
        stone = {"registry": "minecraft:stone", "semantic": "stone", "metadata": 0, "x": 8, "y": 1, "z": 4}
        self.assertEqual(layout([stone]), {(8, 1, 4, "stone")})
        for cells in ([dict(stone, metadata=2)], [stone, stone], [dict(stone, registry="other:stone")]):
            with self.subTest(cells=cells), self.assertRaises(ValueError): layout(cells)


def batch_reports(additional_failure=False):
    tests = [{"classname": "tlmnhqapilot:FollowMechanismPortTests", "name": label + suffix,
              "required": True, "status": "passed"}
             for label in ("faithful", "enhanced")
             for suffix in ("TemporaryObstacle", "OwnerReversal", "WaitingOwnerFood")]
    if additional_failure:
        tests.append({"classname": "tlmnhqapilot:OtherTests", "name": "other", "required": True,
                      "status": "failed", "failure": {"message": "retained independent failure"}})
    cases = []
    for test in tests:
        test["id"] = test["classname"] + "." + test["name"]
        case = ET.Element("testcase", classname=test["classname"], name=test["name"])
        if test["status"] == "failed":
            ET.SubElement(case, "failure")
        cases.append(case)
    failed = int(additional_failure)
    counts = dict(selectedTests=len(tests), passed=6, failed=failed, requiredFailures=failed,
                  optionalFailures=0, timedOut=0, skipped=0, incomplete=0, issues=0, diagnosticErrors=0,
                  junitFailures=failed, junitErrors=0, junitSkipped=0)
    report = dict(status="failed" if failed else "passed", exitCode=failed, counts=counts, tests=tests)
    observed = dict(status=report, process_exit=failed, full_release_passed=False)
    return report, observed, cases


class BatchScopeCounterexamples(unittest.TestCase):

    def test_only_known_null_omission_is_accepted_with_exact_resource_hash(self):
        contract = {"noninferiorityDelta": None, "frames": 240}
        actual = {"profile": "enhanced", "contract": {"frames": 240}, "contractSha256": "exact"}
        check_captured_contract(actual, contract, "exact", "enhanced")
        for replacement in ({"frames": 240, "noninferiorityDelta": .1}, {}, {"frames": 239}):
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                check_captured_contract(dict(actual, contract=replacement), contract, "exact", "enhanced")
        with self.assertRaises(ValueError):
            check_captured_contract(actual, contract, "different", "enhanced")

    def test_complete_selected_run_preserves_original_contract(self):
        result = verify_port_results(*batch_reports())
        self.assertTrue(result["whole_batch_passed"])

    def test_explicit_scoped_read_retains_unrelated_failure_and_failed_process(self):
        args = batch_reports(True)
        result = verify_port_results(*args, allow_additional=True, allow_failed_additional=True)
        self.assertFalse(result["whole_batch_passed"])
        self.assertEqual(result["process_exit"], 1)
        self.assertEqual(result["additional_failures"][0]["failure"]["message"], "retained independent failure")
        with self.assertRaises(ValueError):
            verify_port_results(*args, allow_additional=True)
        with self.assertRaises(ValueError):
            verify_port_results(*args, allow_failed_additional=True)

    def test_selected_failure_cannot_be_hidden_in_batch(self):
        report, observed, cases = batch_reports(True)
        report["tests"][0]["status"] = "failed"
        with self.assertRaises(ValueError):
            verify_port_results(report, observed, cases, True, True)

    def test_process_report_and_junit_disagreement_are_rejected(self):
        for change in ("process", "xml", "incomplete", "counts"):
            report, observed, cases = batch_reports(True)
            if change == "process": observed["process_exit"] = 0
            if change == "xml": cases[-1].remove(cases[-1].find("failure"))
            if change == "incomplete": report["counts"]["incomplete"] = 1
            if change == "counts": report["counts"]["failed"] = 0
            with self.subTest(change=change), self.assertRaises(ValueError):
                verify_port_results(report, observed, cases, True, True)


if __name__ == "__main__":
    unittest.main()
