"""Rich overlay updater adapted for the isolated TLM scope.

Based on the supplied GTNH tools/update.py, SHA-256
fda4960b32dd14be6b47868b2edeabc411a5928c955643c371309607a1a91544.
The merge helpers and before.zip/receipt.json workflow are retained.
"""
from pathlib import Path, PurePosixPath
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

try:
    from rich.console import Console
    from rich.prompt import Prompt, Confirm
    from rich.table import Table
except ImportError:
    # Importable for headless checks; interactive use still requires the original Rich interface.
    Console = Prompt = Confirm = Table = None

SCHEMA = "tlmnh-overlay-v1"
console = Console() if Console else None


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    return digest(path.read_bytes())


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def merge(text, changes, separator):
    for key, value in changes.items():
        pattern = r'^([ \t]*)' + re.escape(key) + re.escape(separator) + r'.*$'
        line = key + separator + str(value)
        text = re.sub(pattern, lambda m: m.group(1) + line, text, flags=re.M) if re.search(pattern, text, re.M) else text.rstrip()+'\n'+line+'\n'
    return text


def merge_sections(text, patch):
    def section(match):
        name = re.match(r'^\[([^\]]+)\]', match.group(1)).group(1)
        values = dict(patch['values'])
        values.update(patch.get('rank_overrides', {}).get(name, {}))
        return match.group(1)+merge(match.group(2), values, patch['separator']).rstrip()+'\n\n'
    return re.sub(r'(?ms)(^\[[^\]\r\n]+\][ \t]*\r?\n)(.*?)(?=^\[|\Z)', section, text)


def merge_forge_sections(text, sections, create_missing=False):
    for name, values in sections.items():
        pattern = r'(?ms)(^'+re.escape(name)+r'[ \t]*\{[ \t]*\r?\n)(.*?)(^\})'
        text, count = re.subn(pattern, lambda m: m.group(1)+merge(m.group(2), values, '=')+m.group(3), text)
        if count == 0 and create_missing:
            text = text.rstrip()+'\n\n'+name+' {\n'+''.join('    '+k+'='+str(v)+'\n' for k, v in values.items())+'}\n'
        elif count != 1:
            raise ValueError('无法唯一定位 Forge 配置节: '+name)
    return text


def member(name):
    require(isinstance(name, str) and bool(name), "空的包路径")
    path = PurePosixPath(name)
    require(not path.is_absolute() and path.as_posix() == name and ".." not in path.parts
            and "\\" not in name and ":" not in name and not any(ord(c) < 32 for c in name), "不安全的包路径: " + name)
    for part in path.parts:
        require(not part.endswith((".", " ")) and not re.fullmatch(r"(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", part),
                "Windows 路径别名不能用于更新: " + name)
    return path


def safe(root, name):
    path = member(name)
    cursor = root
    for part in path.parts:
        cursor = cursor / part
        # Root may be an explicitly selected resolved instance; redirects underneath it are never followed.
        require(not cursor.is_symlink() and not getattr(cursor, "is_junction", lambda: False)(),
                "更新路径含符号链接或 Junction: " + name)
    resolved = cursor.resolve()
    require(root in resolved.parents, "路径越过目标目录: " + name)
    return resolved


def hash_map(values):
    require(isinstance(values, dict), "文件摘要清单格式错误")
    seen = set()
    for name, value in values.items():
        member(name)
        require(name.casefold() not in seen, "重复的 Windows 路径: " + name)
        seen.add(name.casefold())
        require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "无效 SHA-256: " + name)
    return values


def mod_prefix(kind):
    return ".minecraft/mods/" if kind == "client" else "mods/"


def payload_path(kind, name):
    member(name)
    jar = name.startswith(mod_prefix(kind)) and "/" not in name[len(mod_prefix(kind)):] and re.fullmatch(
        r"tlmnh-[A-Za-z0-9._-]+\.jar", name[len(mod_prefix(kind)):])
    return bool(jar) or name.startswith(".tlmnh-distribution/" + kind + "/")


def manifest_at(package):
    manifest = json.loads(safe(package, "manifest.json").read_bytes())
    require(manifest.get("schema") == SCHEMA and manifest.get("scope") == "tlmnh", "只接受独立 TLM 更新包")
    require(manifest.get("kind") in ("client", "server") and manifest.get("patches") == {}, "TLM 包不能改写整包配置")
    require(manifest.get("channel") in ("local-validation", "release"), "未知更新通道")
    if manifest["channel"] == "release":
        require(manifest.get("distributable") is True and manifest.get("release_blockers") == [], "正式发布准入尚未完成")
    else:
        require(manifest.get("distributable") is False, "本地验证包不能标为正式分发")
    require(not str(manifest.get("status", "")).startswith("awaiting"), "该包尚未完成完整性核对")
    kind = manifest["kind"]
    files = hash_map(manifest.get("files"))
    jar = manifest.get("jar", {})
    require(jar.get("path") in files and jar.get("sha256") == files[jar["path"]]
            and jar["path"].startswith(mod_prefix(kind)) and jar["path"].endswith(".jar"), "主 JAR 摘要不匹配")
    require(sum(name.startswith(mod_prefix(kind)) for name in files) == 1, "每端只允许一个 TLM JAR")
    require(all(payload_path(kind, name) for name in files), "包试图管理 TLM 范围外的文件")
    for key in ("remove_files", "replace_files"):
        entries = manifest.get(key, {})
        require(isinstance(entries, dict), "旧 JAR 清单格式错误")
        for name, values in entries.items():
            require(payload_path(kind, name) and name.startswith(mod_prefix(kind)), "旧版本只能指定 TLM JAR")
            require(isinstance(values, list) and bool(values), "旧版本必须列出精确 SHA-256")
            for value in values:
                hash_map({name: value})
            require((name in files) == (key == "replace_files"), "旧版本替换与安装路径冲突: " + name)
    return manifest


def target_root(target, kind):
    target = Path(target).resolve(strict=True)
    if kind == "client" and target.name.casefold() == ".minecraft":
        target = target.parent
    require(target.is_dir(), "目标不是目录")
    marker = "mmc-pack.json" if kind == "client" else "lwjgl3ify-forgePatches.jar"
    require(safe(target, marker).is_file(), "目标不是匹配的 GTNH 实例或服务端")
    return target


def current_hash(path):
    if not path.exists():
        return None
    require(path.is_file(), "目标文件被目录占用: " + path.name)
    return sha(path)


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


@contextmanager
def update_lock(target, kind):
    path = safe(target, ".tlmnh-update-lock-" + kind)
    try:
        with path.open("xb") as stream:
            stream.write(b"0")
    except FileExistsError:
        pass
    require(path.read_bytes() == b"0", "未知更新锁文件，保留原文件")
    with path.open("r+b") as stream:
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("另一更新或恢复操作正在使用该目标") from error
        try:
            yield
        finally:
            if os.name == "nt":
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def process_created_if_running(pid):
    """Query one recorded PID, never its command line or unrelated processes."""
    require(isinstance(pid, int) and not isinstance(pid, bool) and 0 < pid <= 0xFFFFFFFF, "启动记录 PID 无效")
    require(os.name == "nt", "当前平台不能核对该 Windows 启动记录，请先确认目标已正常退出")
    import ctypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel.GetProcessTimes.argtypes = [ctypes.c_void_p, *([ctypes.POINTER(ctypes.c_ulonglong)] * 4)]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        error = ctypes.get_last_error()
        if error == 87:
            return None
        if error == 5:
            # Existing launcher identity method: only this PID's creation date; no command line is requested.
            query = ("$ErrorActionPreference='Stop'; $p=Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId = "
                     + str(pid) + "'; if (-not $p) {'ABSENT'} elseif ($p.CreationDate) {"
                     "$p.CreationDate.ToUniversalTime().ToString('o')}")
            try:
                result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", query],
                                        capture_output=True, text=True, encoding="utf-8", timeout=10, check=True)
                created = result.stdout.strip()
                if created == "ABSENT":
                    return None
                return datetime.fromisoformat(created).timestamp()
            except (OSError, ValueError, subprocess.SubprocessError) as failure:
                raise ValueError("无法核对所选目标的启动记录，请先核实进程已退出") from failure
        raise ValueError("无法读取所选目标的进程身份，请先核实进程已退出")
    try:
        code = ctypes.c_ulong()
        created, exited, system, user = (ctypes.c_ulonglong() for _ in range(4))
        require(kernel.GetExitCodeProcess(handle, ctypes.byref(code))
                and kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                           ctypes.byref(system), ctypes.byref(user)), "无法核对所选目标的进程时间")
        return created.value / 10_000_000 - 11_644_473_600 if code.value == 259 and exited.value == 0 else None
    finally:
        kernel.CloseHandle(handle)


def require_recorded_processes_stopped(target, kind):
    metadata = safe(target, "process.json")
    if not metadata.exists():
        return
    state = json.loads(metadata.read_bytes())
    if not isinstance(state, dict) or state.get("side") != kind or not isinstance(state.get("cwd"), str):
        return
    game_root = safe(target, ".minecraft") if kind == "client" else target
    if os.path.normcase(str(Path(state["cwd"]).resolve())) != os.path.normcase(str(game_root)):
        return
    require(isinstance(state.get("started_at"), str), "所选目标的启动时间记录不完整")
    started = datetime.fromisoformat(state["started_at"])
    require(started.tzinfo is not None, "所选目标的启动时间缺少时区")
    boundary = started
    if state.get("finished_at"):
        boundary = datetime.fromisoformat(state["finished_at"])
        require(boundary.tzinfo is not None and boundary >= started, "所选目标的退出时间记录不完整")
    for key in ("java", "supervisor"):
        require(key in state, "所选目标的进程记录不完整")
        created = process_created_if_running(state[key])
        # A later process reusing an ended (or abandoned) launcher's PID is not this recorded launch.
        require(created is None or created > boundary.timestamp(), "所选目标记录的进程仍在运行，请先正常退出")


def require_stopped(target, kind):
    """Reject recognized live launches/additional held locks; this is not proof that every launcher exited."""
    require_recorded_processes_stopped(target, kind)
    name = ".minecraft/session.lock"
    if kind == "server":
        level = "World"
        properties = safe(target, "server.properties")
        if properties.exists():
            for line in properties.read_text(encoding="utf-8-sig").splitlines():
                if line.startswith("level-name="):
                    level = line.split("=", 1)[1]
        name = member(level).as_posix() + "/session.lock"
    lock = safe(target, name)
    if lock.exists() and os.name == "nt":
        import msvcrt
        try:
            with lock.open("r+b") as stream:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        except OSError as error:
            raise ValueError("实例正在运行，请先正常退出") from error


def tlm_jar(path):
    if path.name.casefold().startswith("tlmnh"):
        return True
    try:
        with zipfile.ZipFile(path) as archive:
            if "mcmod.info" not in archive.namelist():
                return False
            value = json.loads(archive.read("mcmod.info"))
        mods = value if isinstance(value, list) else value.get("modList", [value])
        return any(isinstance(mod, dict) and mod.get("modid") == "tlmnh" for mod in mods)
    except (OSError, ValueError, KeyError, AttributeError, zipfile.BadZipFile):
        return False


def pending_backups(target, kind):
    folder = safe(target, ".tlmnh-update-backups/" + kind)
    result = []
    if folder.exists():
        for path in sorted(folder.glob("*/receipt.json")):
            receipt = json.loads(safe(target, path.relative_to(target).as_posix()).read_bytes())
            require(receipt.get("scope") == "tlmnh" and receipt.get("kind") == kind, "未知 TLM 备份记录，保留原文件")
            if receipt.get("status") not in ("applied", "rolled-back"):
                result.append(path.parent)
    return result


def plan(package, target, manifest):
    kind = manifest["kind"]
    state_name = ".tlmnh-managed-" + kind + ".json"
    state_file = safe(target, state_name)
    previous = {}
    authority = {state_name: None}
    if state_file.exists():
        raw_state = state_file.read_bytes()
        authority[state_name] = digest(raw_state)
        state = json.loads(raw_state)
        require(state.get("schema") == 1 and state.get("scope") == "tlmnh" and state.get("kind") == kind, "未知 TLM 托管记录")
        previous = hash_map(state.get("files"))
        require(all(payload_path(kind, name) for name in previous), "旧 TLM 记录含范围外文件")
        prior_jar = state.get("jar", {})
        require(prior_jar.get("path") in previous and prior_jar.get("sha256") == previous[prior_jar["path"]]
                and prior_jar["path"].startswith(mod_prefix(kind))
                and sum(name.startswith(mod_prefix(kind)) for name in previous) == 1, "旧 TLM 主 JAR 记录不完整")
    names = set(previous) | set(manifest["files"]) | set(manifest.get("remove_files", {})) | {state_name}
    require(len({name.casefold() for name in names}) == len(names), "托管清单含重复的 Windows 路径")
    edits = {}
    for name, expected in previous.items():
        actual = current_hash(safe(target, name))
        authority[name] = actual
        require(actual in (None, expected), "旧 TLM 托管文件已修改，保留原文件: " + name)
        if name not in manifest["files"]:
            edits[name] = None
    for name, known in manifest.get("remove_files", {}).items():
        actual = current_hash(safe(target, name))
        authority[name] = actual
        require(actual is None or actual in known, "待替换的旧 JAR 摘要不匹配: " + name)
        edits[name] = None
    for name, expected in manifest["files"].items():
        src = safe(package, name)
        require(src.is_file(), "缺少包文件: " + name)
        data = src.read_bytes()
        require(digest(data) == expected, "包文件校验失败: " + name)
        actual = current_hash(safe(target, name))
        authority[name] = actual
        require(actual is None or actual == expected or previous.get(name) == actual
                or actual in manifest.get("replace_files", {}).get(name, []), "目标存在未知文件，保留原文件: " + name)
        edits[name] = data
    # Reject renamed duplicate mod IDs as well as unknown tlmnh*.jar names.
    mods = safe(target, mod_prefix(kind).rstrip("/"))
    permitted = {manifest["jar"]["path"]} | set(manifest.get("remove_files", {})) | {n for n in previous if n.startswith(mod_prefix(kind))}
    if mods.exists():
        for path in mods.iterdir():
            if path.suffix.casefold() == ".jar":
                path = safe(target, path.relative_to(target).as_posix())
                require(not tlm_jar(path) or path.relative_to(target).as_posix() in permitted, "发现未知或重复 TLM JAR: " + path.name)
    receipt = dict(schema=1, scope="tlmnh", kind=kind, files=manifest["files"], jar=manifest["jar"], channel=manifest["channel"])
    edits[state_name] = encoded(receipt)
    changes = {name: data for name, data in edits.items() if authority[name] != (digest(data) if data is not None else None)}
    return changes, {name: authority[name] for name in changes}


def restore_locked(target, backup, kind):
    expected_root = safe(target, ".tlmnh-update-backups/" + kind)
    backup = Path(backup).resolve(strict=True)
    require(backup.parent == expected_root, "备份不属于该目标的 TLM 范围")
    backup = safe(target, backup.relative_to(target).as_posix())
    receipt_path = safe(backup, "receipt.json")
    receipt = json.loads(receipt_path.read_bytes())
    require(receipt.get("schema") == 1 and receipt.get("scope") == "tlmnh" and receipt.get("kind") == kind
            and isinstance(receipt.get("target"), str)
            and os.path.normcase(receipt["target"]) == os.path.normcase(str(target))
            and receipt.get("status") in ("prepared", "applied", "restoring", "rolled-back"), "备份所属目标或格式不匹配")
    changed = receipt.get("changed", [])
    before, after = receipt.get("before"), receipt.get("after")
    require(isinstance(changed, list) and bool(changed) and all(isinstance(name, str) for name in changed)
            and len(changed) == len({name.casefold() for name in changed}) and isinstance(before, dict) and isinstance(after, dict)
            and set(changed) == set(before) == set(after), "备份文件清单不完整")
    for name in changed:
        require(payload_path(kind, name) or name == ".tlmnh-managed-" + kind + ".json", "备份含 TLM 范围外文件")
        for value in (before[name], after[name]):
            if value is not None:
                hash_map({name: value})
    existed = {name for name in changed if before[name] is not None}
    require(isinstance(receipt.get("existed"), list) and set(receipt["existed"]) == existed, "备份原文件清单不完整")
    payload = {}
    with zipfile.ZipFile(safe(backup, "before.zip")) as archive:
        require(len(archive.namelist()) == len(existed) and set(archive.namelist()) == existed, "备份归档成员不匹配")
        for name in existed:
            data = archive.read(name)
            require(digest(data) == before[name], "备份原字节摘要不匹配: " + name)
            payload[name] = data
    # Preflight every successor before restoring any file. Interrupted restores can be resumed.
    for name in changed:
        require(current_hash(safe(target, name)) in (before[name], after[name]), "恢复遇到未知后继，保留文件和备份: " + name)
    receipt["status"] = "restoring"
    atomic(receipt_path, encoded(receipt))
    for name in reversed(changed):
        dst = safe(target, name)
        actual = current_hash(dst)
        require(actual in (before[name], after[name]), "恢复期间文件发生变化: " + name)
        if actual == before[name]:
            continue
        if before[name] is None:
            dst.unlink()
        else:
            atomic(dst, payload[name])
        require(current_hash(safe(target, name)) == before[name], "恢复读回不匹配: " + name)
    receipt["status"] = "rolled-back"
    atomic(receipt_path, encoded(receipt))


def apply(package, target):
    package = Path(package).resolve(strict=True)
    manifest = manifest_at(package)
    target = target_root(target, manifest["kind"])
    require(package != target and package not in target.parents and target not in package.parents, "更新包和目标目录不能互相包含")
    kind = manifest["kind"]
    require_stopped(target, kind)
    require(not pending_backups(target, kind), "存在未完成的 TLM 更新，请先回滚对应备份")
    changes, before = plan(package, target, manifest)
    if not changes:
        return None
    with update_lock(target, kind):
        require_stopped(target, kind)
        require(not pending_backups(target, kind), "另一更新留下未完成备份")
        changes, before = plan(package, target, manifest)
        if not changes:
            return None
        original = {name: safe(target, name).read_bytes() if safe(target, name).exists() else None for name in changes}
        require(all((digest(data) if data is not None else None) == before[name] for name, data in original.items()),
                "备份前目标发生变化，保留现文件")
        backup = safe(target, ".tlmnh-update-backups/" + kind + "/" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
        backup.mkdir(parents=True, exist_ok=False)
        with zipfile.ZipFile(backup / "before.zip", "x", zipfile.ZIP_DEFLATED) as archive:
            for name, data in original.items():
                if data is not None:
                    archive.writestr(name, data)
        with (backup / "before.zip").open("r+b") as stream:
            os.fsync(stream.fileno())
        receipt = dict(schema=1, scope="tlmnh", kind=kind, target=str(target), status="prepared",
                       changed=list(changes), existed=[name for name in changes if original[name] is not None], manifest=manifest,
                       before={n: digest(b) if b is not None else None for n, b in original.items()},
                       after={n: digest(b) if b is not None else None for n, b in changes.items()})
        receipt_path = backup / "receipt.json"
        atomic(receipt_path, encoded(receipt))
        try:
            for name, data in changes.items():
                dst = safe(target, name)
                require(current_hash(dst) == receipt["before"][name], "更新期间文件发生变化: " + name)
                if data is None:
                    dst.unlink()
                else:
                    atomic(dst, data)
                require(current_hash(safe(target, name)) == receipt["after"][name], "更新读回不匹配: " + name)
            require(all(current_hash(safe(target, name)) == value for name, value in manifest["files"].items()),
                    "更新完成前托管文件发生变化")
            require(all(current_hash(safe(target, name)) == receipt["after"][name] for name in changes),
                    "更新完成前旧 JAR 或托管记录发生变化")
            receipt["status"] = "applied"
            atomic(receipt_path, encoded(receipt))
        except Exception as failure:
            try:
                restore_locked(target, backup, kind)
            except Exception as recovery:
                raise RuntimeError("更新失败且自动恢复未完成；原文件与备份已保留，请处理未知后继后回滚: " + str(backup)) from recovery
            raise failure
        return backup


def restore(target, backup):
    backup = Path(backup).resolve(strict=True)
    receipt = json.loads(safe(backup, "receipt.json").read_bytes())
    require(receipt.get("kind") in ("client", "server"), "未知备份端别")
    kind = receipt["kind"]
    target = target_root(target, kind)
    require_stopped(target, kind)
    with update_lock(target, kind):
        restore_locked(target, backup, kind)


def main():
    if console is None:
        raise ValueError("请先运行：python -m pip install rich")
    package = Path(__file__).resolve().parent
    manifest = manifest_at(package)
    kind = manifest["kind"]
    console.rule('[bold cyan]TLM · ' + ('客户端' if kind == 'client' else '服务端') + '增量更新')
    if manifest["channel"] == "local-validation":
        console.print('[yellow]Indev 本地验证包，尚未准入正式发布')
        for blocker in manifest.get("release_blockers", []):
            console.print("  " + str(blocker.get("code", blocker)))
    console.print('请先正常退出目标游戏或服务端，再拖入实例目录或输入路径；每次修改前自动备份')
    console.print('检查只覆盖匹配的启动记录和可识别的锁；没有记录也不能证明其他启动器已经退出')
    action = Prompt.ask('操作', choices=['更新', '回滚', '退出'], default='更新')
    if action == '退出':
        return
    target = target_root(Prompt.ask('目标实例目录').strip().strip('"').strip("'"), kind)
    if action == '回滚':
        folder = safe(target, ".tlmnh-update-backups/" + kind)
        backups = sorted(folder.glob('*/receipt.json'), reverse=True)
        require(bool(backups), '没有可恢复的 TLM 备份')
        for index, path in enumerate(backups):
            console.print(f'{index + 1}. {path.parent.name}')
        choice = Prompt.ask('恢复序号', choices=[str(i + 1) for i in range(len(backups))], default='1')
        if Confirm.ask('恢复此备份？'):
            restore(target, backups[int(choice) - 1].parent)
            console.print('[green]已恢复')
    else:
        table = Table('内容', '说明')
        for name in manifest['files']:
            table.add_row(name, '核对摘要，备份后写入')
        for name in manifest.get('remove_files', {}):
            table.add_row(name, '只移除精确匹配的旧 TLM JAR')
        console.print(table)
        if Confirm.ask(f'已正常退出目标，应用到 {target}？'):
            backup = apply(package, target)
            console.print('[green]更新完成，备份：' + str(backup) if backup else '[green]已是最新，无需修改')


if __name__ == '__main__':
    exit_code = 0
    try:
        main()
    except Exception as error:
        exit_code = 1
        if console:
            console.print(f'[red]操作失败：{error}')
        else:
            print('操作失败：' + str(error))
    input('按回车退出…')
    raise SystemExit(exit_code)
