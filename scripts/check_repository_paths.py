"""检查 Git 跟踪的文本文件，防止提交本机绝对路径；错误只输出相对文件名与行号"""

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ABSOLUTE_PATH = re.compile(rb"(?i)(?<![a-z0-9])(?:[a-z]:[\\/]|/(?:Users|home|Volumes)/)")


def main():
    result = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"], check=True, capture_output=True
    )
    checked = 0
    failures = []
    for raw_name in result.stdout.split(b"\0"):
        if not raw_name:
            continue
        name = raw_name.decode("utf-8")
        path = ROOT / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" in data:
            continue
        checked += 1
        failures.extend(
            f"{name}:{number}"
            for number, line in enumerate(data.splitlines(), 1)
            if ABSOLUTE_PATH.search(line)
        )
    if failures:
        print("Local absolute paths found:")
        print("\n".join(failures))
        return 1
    print(f"REPOSITORY_PATH_CHECK_PASS text_files={checked}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
