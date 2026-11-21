#!/usr/bin/env python3
"""Готовит ffmpeg для Android из репозитория Termux и раскладывает его в assets APK.

Зачем: AAR youtubedl-android 0.18.1 несёт ffmpeg 7.1.1 (старая сборка). Termux раздаёт
актуальные сборки (сейчас 9.0.2) для тех же ABI. Скрипт скачивает пакет `ffmpeg` и его
зависимости, оставляет `usr/bin/ffmpeg` + `usr/bin/ffprobe` и только те `.so`, которые
реально нужны линкеру (замыкание DT_NEEDED), и пишет в `app/src/main/assets/ffmpeg9/`:

    ffmpeg             бинарь (приложение копирует его в filesDir и запускает через
                       /system/bin/linker64 — прямой exec из данных запрещён SELinux)
    ffprobe            то же для ffprobe
    lib/<файл>         реальные файлы библиотек
    lib/symlinks.txt   «soname<TAB>реальный файл» — симлинки приложение создаёт само
    version.txt        версия ffmpeg из пакета (например, 9.0.2)

Запуск:  python tools/fetch_ffmpeg.py
"""
from __future__ import annotations

import argparse
import io
import lzma
import re
import struct
import sys
import tarfile
import urllib.request
from pathlib import Path

BASE = "https://packages.termux.dev/apt/termux-main"
ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".cache"
OUT = ROOT / "app" / "src" / "main" / "assets" / "ffmpeg9"

# Библиотеки, которые даёт сама Android: в APK их не кладём.
SYSTEM_LIBS = {
    "libc.so",
    "libm.so",
    "libdl.so",
    "liblog.so",
    "libandroid.so",
    "libmediandk.so",
    "libjnigraphics.so",
    "libEGL.so",
    "libGLESv2.so",
    "libOpenSLES.so",
    "libz.so",
}


def http_get(url: str) -> bytes:
    print(f"  GET {url}")
    with urllib.request.urlopen(url, timeout=180) as response:
        return response.read()


def parse_packages(text: str) -> dict[str, dict[str, str]]:
    packages: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        info: dict[str, str] = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                info[key.strip()] = value.strip()
        name = info.get("Package")
        if name:
            packages[name] = info
    return packages


def dependency_closure(packages: dict[str, dict[str, str]], root: str) -> list[str]:
    seen: list[str] = []
    queue = [root]
    while queue:
        name = queue.pop()
        if name in seen or name not in packages:
            continue
        seen.append(name)
        for dep in packages[name].get("Depends", "").split(","):
            dep = dep.strip()
            if not dep:
                continue
            candidate = re.split(r"[\s(:]", dep.split("|")[0].strip())[0]
            if candidate:
                queue.append(candidate)
    return seen


def ar_members(data: bytes):
    assert data[:8] == b"!<arch>\n", "не ar-архив"
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos : pos + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        start = pos + 60
        yield name, data[start : start + size]
        pos = start + size + (size & 1)


def deb_files(deb: bytes) -> dict[str, tuple[str, bytes]]:
    """{путь: (тип, содержимое|цель симлинка)} только для usr/bin и usr/lib."""
    result: dict[str, tuple[str, bytes]] = {}
    for name, payload in ar_members(deb):
        if not name.startswith("data.tar"):
            continue
        raw = lzma.decompress(payload) if name.endswith(".xz") else payload
        with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
            for member in tar:
                path = member.name.lstrip("./")
                prefix = "data/data/com.termux/files/"
                if path.startswith(prefix):
                    path = path[len(prefix) :]
                if not (path.startswith("usr/lib/") or path.startswith("usr/bin/")):
                    continue
                if member.issym():
                    result[path] = ("sym", member.linkname.encode())
                elif member.isfile():
                    handle = tar.extractfile(member)
                    if handle is not None:
                        result[path] = ("file", handle.read())
    return result


def elf_needed(data: bytes) -> list[str]:
    """DT_NEEDED через program headers (PT_DYNAMIC): у stripped-библиотек нет секций."""
    if data[:4] != b"\x7fELF":
        return []
    endian = "<" if data[5] == 1 else ">"
    if data[4] != 2:
        raise SystemExit("поддерживаются только 64-битные ELF")
    phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)

    loads: list[tuple[int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz, _memsz, _align = struct.unpack_from(
            endian + "IIQQQQQQ", data, offset
        )
        if p_type == 1:  # PT_LOAD
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:  # PT_DYNAMIC
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return []

    def to_offset(vaddr: int) -> int | None:
        for seg_vaddr, seg_offset, seg_size in loads:
            if seg_vaddr <= vaddr < seg_vaddr + seg_size:
                return seg_offset + (vaddr - seg_vaddr)
        return None

    strtab = strsz = None
    entries: list[tuple[int, int]] = []
    dyn_offset, dyn_size = dynamic
    for index in range(dyn_size // 16):
        tag, value = struct.unpack_from(endian + "QQ", data, dyn_offset + index * 16)
        if tag == 0:
            break
        entries.append((tag, value))
        if tag == 5:
            strtab = value
        elif tag == 10:
            strsz = value

    if strtab is None or strsz is None:
        return []
    str_offset = to_offset(strtab)
    if str_offset is None:
        return []

    needed: list[str] = []
    for tag, value in entries:
        if tag != 1:
            continue
        end = data.index(b"\x00", str_offset + value)
        needed.append(data[str_offset + value : end].decode())
    return needed



def resolve_soname(
    soname: str,
    real_by_name: dict[str, str],
    sym_target: dict[str, Path],
    depth: int = 0,
) -> str | None:
    """soname → имя реального файла (симлинки Termux относительные, цепочка до 8 шагов)."""
    if soname in real_by_name:
        return soname
    target = sym_target.get(soname)
    if target is None or depth > 8:
        return None
    return resolve_soname(target.name, real_by_name, sym_target, depth + 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", default="aarch64")
    args = parser.parse_args()

    CACHE.mkdir(parents=True, exist_ok=True)
    index_path = CACHE / f"Packages-{args.arch}"
    if not index_path.exists():
        print("Индекс пакетов Termux…")
        index_path.write_bytes(http_get(f"{BASE}/dists/stable/main/binary-{args.arch}/Packages"))
    packages = parse_packages(index_path.read_text(encoding="utf-8", errors="replace"))

    closure = dependency_closure(packages, "ffmpeg")
    print(f"Пакетов в замыкании: {len(closure)}")

    files: dict[str, tuple[str, bytes]] = {}
    for name in closure:
        filename = packages[name]["Filename"]
        deb_path = CACHE / Path(filename).name
        if not deb_path.exists():
            deb_path.write_bytes(http_get(f"{BASE}/{filename}"))
        files.update(deb_files(deb_path.read_bytes()))

    version = packages["ffmpeg"]["Version"].split("-")[0]
    print(f"Версия ffmpeg: {version}")

    binaries: dict[str, bytes] = {}
    for exe in ("ffmpeg", "ffprobe"):
        kind, data = files[f"usr/bin/{exe}"]
        assert kind == "file", f"{exe} должен быть обычным файлом"
        binaries[exe] = data

    real_by_name = {
        Path(path).name: path
        for path, (kind, _) in files.items()
        if kind == "file" and path.startswith("usr/lib/")
    }
    sym_target = {
        Path(path).name: Path(path).parent / target.decode()
        for path, (kind, target) in files.items()
        if kind == "sym" and path.startswith("usr/lib/")
    }

    needed: list[str] = []
    queue: list[str] = []
    for data in binaries.values():
        queue.extend(elf_needed(data))
    while queue:
        soname = queue.pop()
        if soname in needed or soname in SYSTEM_LIBS:
            continue
        real = resolve_soname(soname, real_by_name, sym_target)
        if real is None:
            print(f"  ! библиотека не найдена в пакетах: {soname}")
            continue
        needed.append(soname)
        queue.extend(elf_needed(files[real_by_name[real]][1]))

    keep = {resolve_soname(soname, real_by_name, sym_target) for soname in needed}
    keep = {name for name in keep if name}

    if OUT.exists():
        for old in OUT.rglob("*"):
            if old.is_file():
                old.unlink()
    lib_dir = OUT / "lib"
    lib_dir.mkdir(parents=True, exist_ok=True)

    for exe, data in binaries.items():
        (OUT / exe).write_bytes(data)

    total = 0
    for name in sorted(keep):
        data = files[real_by_name[name]][1]
        total += len(data)
        (lib_dir / name).write_bytes(data)

    symlink_lines = [
        f"{soname}\t{real}"
        for soname, real in sorted(
            (s, resolve_soname(s, real_by_name, sym_target)) for s in sym_target
        )
        if real in keep
    ]
    (lib_dir / "symlinks.txt").write_text("\n".join(symlink_lines) + "\n", encoding="utf-8")
    (OUT / "version.txt").write_text(version + "\n", encoding="utf-8")

    print(f"Библиотек: {len(keep)}, суммарно {total / 1048576:.1f} МБ")
    print(f"Готово: {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
