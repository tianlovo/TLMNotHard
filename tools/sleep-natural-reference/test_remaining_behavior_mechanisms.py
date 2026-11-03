"""Bounded checker counterexamples; synthetic rows are not runtime or statistical evidence."""

import copy
import unittest

from compare_remaining_behavior_mechanisms import expected_layout, inspect_layout, metrics


def native_rows(mechanism):
    rows = []
    for (x, y, z), name in expected_layout(mechanism).items():
        registry = {"wood_door": "wooden_door", "bed_head": "maid_bed", "bed_foot": "maid_bed"}.get(name, name)
        domain = "tlmnh" if registry == "maid_bed" else "minecraft"
        meta = (0 if y == 1 else 8) if name.endswith("door") else 10 if name == "bed_head" else 2 if name == "bed_foot" else 0
        rows.append(dict(x=x, y=y, z=z, registry=domain + ":" + registry, metadata=meta))
    return rows


class RemainingBehaviorCounterexamples(unittest.TestCase):
    def test_open_door_or_missing_roof_is_not_the_closed_corridor_input(self):
        rows = native_rows("closed_door_recovery")
        inspect_layout(rows, "closed_door_recovery", False)
        changed = copy.deepcopy(rows)
        next(row for row in changed if row["registry"] == "minecraft:iron_door" and row["y"] == 1)["metadata"] = 4
        with self.assertRaises(ValueError):
            inspect_layout(changed, "closed_door_recovery", False)
        with self.assertRaises(ValueError):
            inspect_layout(rows[:-1], "closed_door_recovery", False)

    def test_occupied_bed_and_unknown_liquid_are_not_normalized_away(self):
        rows = native_rows("sleep_defense_crisis")
        inspect_layout(rows, "sleep_defense_crisis", False)
        changed = copy.deepcopy(rows)
        next(row for row in changed if row["registry"] == "tlmnh:maid_bed")["metadata"] |= 4
        with self.assertRaises(ValueError):
            inspect_layout(changed, "sleep_defense_crisis", False)
        changed = copy.deepcopy(rows)
        changed[-1]["registry"] = "minecraft:lava"
        with self.assertRaises(ValueError):
            inspect_layout(changed, "sleep_defense_crisis", False)

    def test_owner_only_migration_does_not_infer_source_exit_or_erase_legal_shots(self):
        data = {"frames": [], "events": [], "combatDamageEvents": [], "migrationFrame": 30,
                "shots": [{"frame": 29}, {"frame": 51}], "behaviorSuccess": True}
        result = metrics(data)
        self.assertFalse(result["source_exit_exercised"])
        self.assertEqual(result["post_migration_shot_joins"], 1)
        self.assertEqual(result["native_shot_joins"], 2)
        self.assertEqual(result["sleep_frame"], -1)


if __name__ == "__main__":
    unittest.main()
