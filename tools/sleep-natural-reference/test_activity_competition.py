"""仅核来源/相位准入的必要负例；合成数据不作为游戏证据。"""

import unittest
import xml.etree.ElementTree as ET

from compare_activity_competition import EXPECTED_NAMES, semantic_layout
from compare_follow_mechanisms import verify_port_results
from test_follow_mechanisms import batch_reports


class ActivityInputCounterexamples(unittest.TestCase):
    def test_existing_hqa_validator_requires_actual_selected_class_and_preserves_failure(self):
        report, observed, cases = batch_reports()
        with self.assertRaises(ValueError):
            verify_port_results(
                report, observed, cases,
                selected_class="tlmnhqapilot:ActivityCompetitionPortTests",
                expected_names=EXPECTED_NAMES,
            )
        for row, case, name in zip(report["tests"], cases, sorted(EXPECTED_NAMES)):
            row.update(classname="tlmnhqapilot:ActivityCompetitionPortTests", name=name)
            row["id"] = row["classname"] + "." + name
            case.set("classname", row["classname"])
            case.set("name", name)
        verify_port_results(
            report, observed, cases,
            selected_class="tlmnhqapilot:ActivityCompetitionPortTests",
            expected_names=EXPECTED_NAMES,
        )
        report["tests"][0]["status"] = "failed"
        ET.SubElement(cases[0], "failure")
        with self.assertRaises(ValueError):
            verify_port_results(
                report, observed, cases, True, True,
                "tlmnhqapilot:ActivityCompetitionPortTests", EXPECTED_NAMES,
            )

    def test_unknown_semantic_or_modified_cake_bites_cannot_be_normalized_away(self):
        cake = dict(x=12, y=2, z=12, semantic="cake", registry="minecraft:cake", metadata=0)
        self.assertEqual(semantic_layout([cake]), {(12, 2, 12, "cake")})
        for rows in ([dict(cake, metadata=1)], [dict(cake, semantic="unknown")], [cake, cake]):
            with self.assertRaises(ValueError):
                semantic_layout(rows)
        original = dict(x=12, y=1, z=12, semantic="snack_cabinet", registry="touhou_little_maid:snack_cabinet",
                        metadata=0, rawBlockState="Block{touhou_little_maid:snack_cabinet}[facing=north,type=1]")
        port = dict(x=12, y=1, z=12, semantic="snack_cabinet", registry="tlmnh:snack_cabinet", metadata=4)
        self.assertEqual(semantic_layout([original]), semantic_layout([port]))
        for row in (dict(port, metadata=0), dict(port, metadata=5), dict(port, metadata=8),
                    dict(original, rawBlockState="Block{touhou_little_maid:snack_cabinet}[facing=north,type=0]")):
            with self.assertRaises(ValueError):
                semantic_layout([row])


if __name__ == "__main__":
    unittest.main()
