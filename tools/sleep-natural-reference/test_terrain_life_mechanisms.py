"""Bounded analysis counterexamples; synthetic data is not runtime or statistical evidence."""

import copy
import unittest

from compare_terrain_life_mechanisms import enclosure_cells, expected_terrain, metrics, pantry_layout, terrain_layout


class TerrainLifeCounterexamples(unittest.TestCase):
    def test_missing_cliff_support_or_top_slab_is_not_the_same_terrain(self):
        for mechanism in ("terrain_circuit", "fire_cliff", "native_creeper"):
            rows = [dict(x=x, y=y, z=z, registry="minecraft:" + block, metadata=0)
                    for (x, y, z), block in expected_terrain(mechanism).items()]
            terrain_layout(rows, mechanism)
            with self.assertRaises(ValueError):
                terrain_layout(rows[1:], mechanism)
        rows = [dict(x=x, y=y, z=z, registry="minecraft:" + block, metadata=0)
                for (x, y, z), block in expected_terrain("terrain_circuit").items()]
        changed = copy.deepcopy(rows)
        slab = next(row for row in changed if row["registry"] == "minecraft:stone_slab")
        slab["metadata"] = 8
        with self.assertRaises(ValueError):
            terrain_layout(changed, "terrain_circuit")

    def test_pantry_glass_is_a_real_required_input_and_does_not_hide_lava(self):
        rows = [dict(x=x, y=0, z=z, registry="minecraft:stone", metadata=0, semantic="stone")
                for x in range(25) for z in range(25)]
        rows += [dict(x=12, y=1, z=12, registry="tlmnh:snack_cabinet", metadata=4, semantic="snack_cabinet"),
                 dict(x=12, y=2, z=12, registry="minecraft:cake", metadata=0, semantic="cake")]
        rows += [dict(x=x, y=y, z=z, registry="minecraft:glass", metadata=0, semantic="glass")
                 for x, y, z in enclosure_cells(25, 8)]
        pantry_layout(rows)
        with self.assertRaises(ValueError):
            pantry_layout(rows[:-1])
        changed = copy.deepcopy(rows)
        changed[-1].update(registry="minecraft:lava", semantic="unknown")
        with self.assertRaises(ValueError):
            pantry_layout(changed)

    def test_capture_completion_does_not_infer_pantry_trigger_or_erase_death(self):
        data = {"frames": [], "observationComplete": True, "terminalReason": "owner_death",
                "behaviorSuccess": False, "terminalActor": {"health": 17}, "damageEvents": []}
        result = metrics(data, {"id": "native_creeper"})
        self.assertEqual(result["terminal_reason"], "owner_death")
        self.assertFalse(result["behavior_success"])
        self.assertEqual(result["observed_frames"], 0)
        self.assertIsNone(result["first_panic_frame"])
        result = metrics(data, {"id": "pantry_near"})
        self.assertFalse(result["first_bite_exercised"])
        self.assertFalse(result["placement_exercised"])
        self.assertIsNone(result["final_consumed_mouths"])


if __name__ == "__main__":
    unittest.main()
