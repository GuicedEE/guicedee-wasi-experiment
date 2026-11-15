"""Download a pinned official Wasmtime release into this checkout; verify SHA-256."""
import hashlib
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "50.0.1"
ARCHIVES = {
    "Windows": ("x86_64-windows.zip", "c3646ee2de8cfcefe532e75852e1fd2728d78c3fcc43251794803a698d1c30ca"),
    "Linux": ("x86_64-linux.tar.xz", "685466455dd42a14774a86c36c8f2e45cbb9bd6d92a850139315df60a75498a5"),
}


def install():
    if os.environ.get("WASMTIME"):
        return Path(os.environ["WASMTIME"]).resolve()
    if platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("Set WASMTIME to a compatible executable on this architecture")
    suffix, expected = ARCHIVES[platform.system()]
    tools = ROOT / ".tools"
    tools.mkdir(exist_ok=True)
    name = f"wasmtime-v{VERSION}-{suffix}"
    archive = tools / name
    if not archive.exists():
        urllib.request.urlretrieve(
            f"https://github.com/bytecodealliance/wasmtime/releases/download/v{VERSION}/{name}", archive)
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Wasmtime archive checksum mismatch: {actual}")
    folder = name.removesuffix(".zip").removesuffix(".tar.xz")
    executable = tools / folder / ("wasmtime.exe" if platform.system() == "Windows" else "wasmtime")
    if not executable.exists():
        if suffix.endswith(".zip"):
            with zipfile.ZipFile(archive) as source:
                source.extractall(tools)
        else:
            with tarfile.open(archive) as source:
                source.extractall(tools, filter="data")
    return executable


if __name__ == "__main__":
    print(install())
