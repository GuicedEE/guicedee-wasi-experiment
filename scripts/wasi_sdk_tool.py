"""Install the pinned wasi-sdk C compiler locally; never modify the system toolchain."""
import hashlib
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
from wasmtime_tool import ROOT

ARCHIVES = {
    "Windows": ("windows", "cccb5c323a9b34f0349a9b09e8804a0a7632c68c3310f4b5f437ed57d7e71d8f"),
    "Linux": ("linux", "b761e3a0721dbae9c09a0059e5fdb2bf917d1b4a8a7b430fb3b5aafb0984b2c4"),
}

def install():
    if os.environ.get("WASI_SDK_PATH"):
        return Path(os.environ["WASI_SDK_PATH"]).resolve()
    if platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("Set WASI_SDK_PATH on this architecture")
    system, expected = ARCHIVES[platform.system()]
    folder = f"wasi-sdk-34.0-x86_64-{system}"
    tools = ROOT / ".tools"
    tools.mkdir(exist_ok=True)
    archive = tools / (folder + ".tar.gz")
    if not archive.exists():
        urllib.request.urlretrieve(
            f"https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/{archive.name}", archive)
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected:
        raise RuntimeError(f"wasi-sdk checksum mismatch: {actual}")
    executable = tools / folder / "bin" / ("clang.exe" if system == "windows" else "clang")
    if not executable.exists():
        with tarfile.open(archive) as source:
            source.extractall(tools, filter="data")
    return tools / folder

if __name__ == "__main__":
    print(install())
