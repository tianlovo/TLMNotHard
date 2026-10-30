"""原版动画实验的本机位置配置，默认把运行数据放在源码之外"""

import os
from pathlib import Path

PROBE = Path(__file__).resolve().parent
PROJECT = PROBE.parent.parent
RUNTIME = Path(os.environ.get("TLMNH_REFERENCE_HOME", PROJECT.parent / ".tlmnh-reference-runtime")).resolve()
UPSTREAM = Path(os.environ.get("TLMNH_REFERENCE_UPSTREAM", PROJECT.parent / "TouhouLittleMaid-reference")).resolve()
EVIDENCE = Path(os.environ.get("TLMNH_REFERENCE_REPORTS", RUNTIME / "evidence")).resolve()
GIT = os.environ.get("TLMNH_REFERENCE_GIT", "git")
GAME = RUNTIME / "client"

for location in (RUNTIME, EVIDENCE):
    if location.is_relative_to(PROJECT):
        raise ValueError("Reference runtime and evidence must remain outside the source checkout")
