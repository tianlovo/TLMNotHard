"""检查资源校验器会拒绝损坏资产、缺失依赖和错误的额外贴图 ID"""

import json
import unittest
from pathlib import Path

from verify_builtin_assets import verify

PROJECT = Path(__file__).resolve().parents[1]


def source_read(path):
    if path.startswith("META-INF/licenses/"):
        return (PROJECT / path.removeprefix("META-INF/")).read_bytes()
    return (PROJECT / "src/main/resources" / path).read_bytes()


class AssetIntegrityTest(unittest.TestCase):
    def test_current_sources(self):
        self.assertTrue(verify(source_read)["passed"])

    def test_changed_texture_rejected(self):
        def changed(path):
            data = source_read(path)
            return data + b"corrupted" if path == "assets/geckolib/textures/entity/winefox.png" else data

        with self.assertRaisesRegex(ValueError, "Resource differs"):
            verify(changed)

    def test_untracked_dependency_rejected(self):
        def changed(path):
            data = source_read(path)
            if path == "assets/tlmnh/builtin-models.json":
                catalog = json.loads(data)
                catalog["packs"][0]["models"][0]["geometry"] = "tlmnh:missing_geometry.json"
                return json.dumps(catalog).encode("utf-8")
            return data

        with self.assertRaisesRegex(ValueError, "Untracked model resource"):
            verify(changed)

    def test_changed_core_item_animation_rejected(self):
        def changed(path):
            data = source_read(path)
            return data + b"corrupted" if path.endswith("smart_slab_has_maid.png.mcmeta") else data

        with self.assertRaisesRegex(ValueError, "Resource differs"):
            verify(changed)

    def test_incorrect_texture_id_rejected(self):
        def changed(path):
            data = source_read(path)
            if path == "assets/tlmnh/builtin-models.json":
                catalog = json.loads(data)
                extra = next(choice for pack in catalog["packs"] for choice in pack["models"]
                             if choice["id"] != choice["base_id"])
                extra["id"] += "0"
                return json.dumps(catalog).encode("utf-8")
            return data

        with self.assertRaisesRegex(ValueError, "Incorrect extra-texture ID"):
            verify(changed)


if __name__ == "__main__":
    unittest.main()
