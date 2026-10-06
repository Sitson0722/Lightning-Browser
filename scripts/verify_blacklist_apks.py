"""Check that every APK contains exactly the blacklist from this checkout."""

from pathlib import Path
from zipfile import ZipFile


def main():
    root = Path(__file__).resolve().parents[1]
    expected = (root / "Distractions websites.txt").read_bytes()
    apks = sorted((root / "app/build/outputs/apk").rglob("*.apk"))
    if not apks:
        raise SystemExit("No APKs found to verify")
    for apk in apks:
        with ZipFile(apk) as archive:
            if archive.read("assets/blacklist.txt") != expected:
                raise SystemExit(f"Bundled blacklist differs from repository list: {apk}")
            if "assets/hosts.txt" in archive.namelist():
                raise SystemExit(f"Obsolete advertising hosts still bundled: {apk}")
        print(f"Verified blacklist: {apk.relative_to(root)}")


if __name__ == "__main__":
    main()
