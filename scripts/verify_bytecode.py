"""检查最终 JAR 的全部类及嵌套 JAR，拒绝超出 Java 8 的字节码"""

import argparse
import hashlib
import io
import json
import struct
import sys
import zipfile
from pathlib import Path

JAVA_8_MAJOR = 52
CLASS_MAGIC = b"\xca\xfe\xba\xbe"
MAX_NESTED_JAR_DEPTH = 8


def inspect_archive(data, location, depth=0):
    """逐条读取类头，嵌套归档使用相同规则并限制递归深度"""
    if depth > MAX_NESTED_JAR_DEPTH:
        raise ValueError(f"Nested JAR depth exceeded: {location}")

    inspected = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            entry_location = f"{location}!/{entry.filename}"
            if entry.filename.endswith(".class"):
                with archive.open(entry) as stream:
                    header = stream.read(8)
                if len(header) != 8 or header[:4] != CLASS_MAGIC:
                    raise ValueError(f"Invalid class header: {entry_location}")
                minor, major = struct.unpack(">HH", header[4:])
                inspected.append({"entry": entry_location, "major": major, "minor": minor})
            elif entry.filename.endswith(".jar"):
                inspected.extend(inspect_archive(archive.read(entry), entry_location, depth + 1))
    return inspected


def verify(path):
    """以实际产物计算哈希和检查结果，空归档不能作为检查通过"""
    artifact = Path(path).resolve(strict=True)
    data = artifact.read_bytes()
    classes = inspect_archive(data, artifact.name)
    invalid = [entry for entry in classes if entry["major"] > JAVA_8_MAJOR or entry["minor"] == 65535]
    return {
        "artifact": str(artifact),
        "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data),
        "class_count": len(classes),
        "major_versions": sorted({entry["major"] for entry in classes}),
        "maximum_allowed_major": JAVA_8_MAJOR,
        "invalid_entries": invalid,
        "passed": bool(classes) and not invalid,
    }


def main():
    """输出机器可读凭据，失败时返回非零退出码"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.artifact)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Bytecode verification failed: {error}", file=sys.stderr)
        return 1

    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
