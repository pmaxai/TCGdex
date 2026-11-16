"""
Checks the native libraries in APKs / app bundles for 16 KB memory pages.

Google Play requires it for new apps and updates, and phones running Android 15+ with
16 KB pages can't load a library that isn't aligned. Checked per 64-bit library
(arm64-v8a, x86_64): every LOAD segment aligned to at least 16 KB; in an APK, a library
stored uncompressed must also start on a 16 KB boundary inside the file.

  python3 check_16kb.py app.aab [more.apk ...]      exit 1 if any library fails
"""
import struct
import sys
import zipfile

PAGE = 16384


def load_alignment(elf: bytes) -> int:
    assert elf[:4] == b"\x7fELF", "not an ELF file"
    assert elf[4] == 2, "not a 64-bit library"
    phoff = struct.unpack_from("<Q", elf, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    aligns = []
    for i in range(phnum):
        p_type = struct.unpack_from("<I", elf, phoff + i * phentsize)[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", elf, phoff + i * phentsize + 0x30)[0])
    return min(aligns) if aligns else 0


def data_offset(path: str, info: zipfile.ZipInfo) -> int:
    with open(path, "rb") as f:
        f.seek(info.header_offset)
        h = f.read(30)
    n, m = struct.unpack_from("<HH", h, 26)
    return info.header_offset + 30 + n + m


def main(paths) -> int:
    bad = 0
    for path in paths:
        print(f"== {path}")
        with zipfile.ZipFile(path) as z:
            libs = [i for i in z.infolist() if i.filename.endswith(".so")
                    and ("/arm64-v8a/" in i.filename or "/x86_64/" in i.filename)]
            if not libs:
                print("   no 64-bit native libraries")
                bad += 1
            for i in sorted(libs, key=lambda i: i.filename):
                a = load_alignment(z.read(i))
                problems = []
                if a < PAGE:
                    problems.append(f"LOAD segments aligned to {a} bytes")
                if path.endswith(".apk") and i.compress_type == zipfile.ZIP_STORED and data_offset(path, i) % PAGE:
                    problems.append("stored uncompressed but not at a 16 KB boundary in the APK")
                bad += bool(problems)
                print(f"   {'OK ' if not problems else 'BAD'} {i.filename:60s} align {a:6d}" +
                      (f"  <- {'; '.join(problems)}" if problems else ""))
    print("all 16 KB-ready" if not bad else f"{bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
