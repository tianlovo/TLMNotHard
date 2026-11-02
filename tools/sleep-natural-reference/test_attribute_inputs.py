"""属性输入检查器的独立反例；不启动Minecraft，也不模拟或认定实体属性系统等价"""

import copy
import json
import unittest
from pathlib import Path

from attribute_inputs import compare_attribute_inputs


def inputs(side):
    # 手工固定实际场景关注值；不调用被测实现生成预期值
    names_and_values = [
        ("max_health", "minecraft:generic.max_health", "generic.maxHealth", 20),
        ("movement_speed", "minecraft:generic.movement_speed", "generic.movementSpeed", 0.699999988079071),
        ("follow_range", "minecraft:generic.follow_range", "generic.followRange", 64),
        ("knockback_resistance", "minecraft:generic.knockback_resistance", "generic.knockbackResistance", 0),
        ("attack_damage", "minecraft:generic.attack_damage", "generic.attackDamage", 2),
    ]
    rows = [{"key": key, "platformName": original if side == "original" else port, "present": True,
             "storage": "supplier_default" if side == "original" else "instance",
             "base": value, "value": value, "modifiers": []}
            for key, original, port, value in names_and_values]
    available = [row["platformName"] for row in rows]
    unknown = [] if side == "original" else ["gtnh.unknownAttribute"]
    raw_attributes = "[]" if side == "original" else "[" + ",".join(
        "{Name:%s,Base:%sd}" % (row["platformName"], row["base"]) for row in rows
    ) + ",{Name:gtnh.unknownAttribute,Base:7d}]"
    return {"schema": "sleep-attribute-inputs-v1", "side": side,
            "samplePhase": "registered_spawn_before_first_natural_tick",
            "rawEntityNBT": "{Attributes:" + raw_attributes + ",OpaqueRetained:{Data:[1b,2b]}}",
            "rawAttributesNBT": raw_attributes,
            "serializedAttributeCount": 0 if side == "original" else 6,
            "rawNbtUnchanged": True, "attributeInstanceMembershipUnchanged": True,
            "availableAttributeNames": available + unknown,
            "materializedAttributeNames": [] if side == "original" else available + unknown,
            "unmappedAttributeNames": unknown, "attributes": rows}


class AttributeInputCounterexamples(unittest.TestCase):

    def setUp(self):
        self.contract = json.loads((Path(__file__).parent / "paired-contract.json").read_text(encoding="utf-8"))["attributeInputs"]
        self.original, self.port = inputs("original"), inputs("port")

    def compare(self):
        return compare_attribute_inputs(self.original, self.port, self.contract)

    def test_lazy_empty_serialization_has_real_defaults_and_unknown_fields_remain_unproven(self):
        original, port = copy.deepcopy(self.original), copy.deepcopy(self.port)
        result = self.compare()
        self.assertTrue(result["selected_initial_attribute_inputs_equal"])
        self.assertEqual(result["serialized_attribute_counts"], {"original": 0, "port": 6})
        self.assertEqual(result["unmapped_actual_attributes"]["port"], ["gtnh.unknownAttribute"])
        self.assertEqual(result["unmapped_actual_attributes_verdict"], "NOT_PROVEN")
        self.assertFalse(result["all_attribute_semantics_evaluated"])
        self.assertFalse(result["full_nbt_semantic_equivalence_evaluated"])
        self.assertEqual((self.original, self.port), (original, port))

    def test_follow_range_difference_is_not_hidden_by_equal_health_and_speed(self):
        self.port["attributes"][2].update(base=32, value=32)
        result = self.compare()
        self.assertFalse(result["selected_initial_attribute_inputs_equal"])
        self.assertEqual({row["key"] for row in result["differences"]}, {"follow_range"})

    def test_float_promotion_difference_stays_visible_inside_a_representation_tolerance(self):
        self.original["attributes"][1].update(base=0.7, value=0.7)
        result = self.compare()
        self.assertEqual(result["status"], "DIFFERENT")
        self.contract["absoluteTolerance"] = 1e-6
        result = self.compare()
        self.assertEqual(result["status"], "WITHIN_NUMERIC_TOLERANCE_ONLY")
        self.assertFalse(result["selected_initial_attribute_inputs_equal"])
        self.assertTrue(result["selected_initial_attribute_inputs_within_numeric_tolerance"])
        observed = next(row for row in result["measurements"] if row["key"] == "movement_speed")
        self.assertEqual(observed["base"]["original"], 0.7)
        self.assertEqual(observed["base"]["port"], 0.699999988079071)
        self.assertEqual(observed["base"]["original_binary64"], "0x1.6666666666666p-1")
        self.assertEqual(observed["base"]["port_binary64"], "0x1.6666660000000p-1")
        self.assertNotEqual(observed["base"]["port_minus_original_exact_decimal"], "0")
        self.assertFalse(observed["base"]["exact_equal"])
        self.assertTrue(observed["base"]["within_tolerance"])

    def test_equal_result_does_not_erase_transient_vs_persistent_modifier_difference(self):
        modifier = {"uuid": "ac4b3c82-4a8a-4bac-bef0-f4af70b48611", "name": "fixture additive damage",
                    "operation": 0, "amount": 1, "persistent": True}
        self.original["attributes"][4].update(value=3, modifiers=[modifier])
        changed = dict(modifier, persistent=False)
        self.port["attributes"][4].update(value=3, modifiers=[changed])
        result = self.compare()
        self.assertEqual([(row["key"], row["field"]) for row in result["differences"]], [("attack_damage", "modifiers")])

    def test_missing_mapping_is_explicitly_unproven(self):
        self.contract["mappings"] = [row for row in self.contract["mappings"] if row["key"] != "movement_speed"]
        result = self.compare()
        self.assertEqual(result["status"], "NOT_PROVEN")
        self.assertFalse(result["selected_initial_attribute_inputs_equal"])
        self.assertEqual(result["missing_or_unproven"][0]["key"], "movement_speed")

    def test_absent_attribute_is_not_fabricated_as_zero(self):
        row = self.port["attributes"][3]
        self.port["availableAttributeNames"].remove(row["platformName"])
        self.port["materializedAttributeNames"].remove(row["platformName"])
        row.update(present=False, storage="absent")
        for field in ("base", "value", "modifiers"):
            del row[field]
        result = self.compare()
        self.assertEqual(result["status"], "NOT_PROVEN")
        row.update(base=0, value=0, modifiers=[])
        with self.assertRaisesRegex(ValueError, "fabricated"):
            self.compare()


if __name__ == "__main__":
    unittest.main()
