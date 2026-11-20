"""Pinned component tooling and WIT inputs for the outbound HTTP experiment."""
import hashlib
import platform
import shutil
import tarfile
import urllib.request
import zipfile
from wasmtime_tool import ROOT

RELEASES = {
    "wit-bindgen": ("0.63.0", {
        "Windows": "213393e4a8b4875a3f19856e943e43067d0315141fca5743e4cfb7795bc9af14",
        "Linux": "bf4efe883ab70d34a4740253911dda20caf895d58a003af58369506b04684cbe"}),
    "wasm-tools": ("1.263.0", {
        "Windows": "0803df158b4dbea6b029d11f4206c414f432f0447af91542817061e4f0bd912e",
        "Linux": "21c036c1740a0552124c79c6e2170cb61c5b016005671a6d5bc06cf642165431"}),
}
ADAPTER_SHA = "0b4fe490390ecc9c2222f969f65b179e658a3327e005efd4ddc959766132a3c4"
WIT_SHA = "1296925df956a326b1a23dccb7bbcf7cd24b8e4618dd8e6bd8e7ed6e31e98acc"

def download(name, url, sha):
    path = ROOT / ".tools" / name
    path.parent.mkdir(exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".part")
        urllib.request.urlretrieve(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != sha:
            raise RuntimeError(f"Download checksum mismatch: {name}")
        temporary.replace(path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != sha:
        raise RuntimeError(f"Cached checksum mismatch: {name}")
    return path

def prepare_tools(directory):
    system = platform.system()
    if platform.machine().lower() not in ("amd64", "x86_64") or system not in ("Windows", "Linux"):
        raise RuntimeError("HTTP probe tooling currently supports Windows/Linux x86_64")
    binaries = {}
    for name, (version, hashes) in RELEASES.items():
        folder = f"{name}-{version}-x86_64-{'windows' if system == 'Windows' else 'linux'}"
        archive_name = folder + (".zip" if system == "Windows" else ".tar.gz")
        archive = download(archive_name, f"https://github.com/bytecodealliance/{name}/releases/download/v{version}/{archive_name}", hashes[system])
        if system == "Windows":
            with zipfile.ZipFile(archive) as source: source.extractall(ROOT / ".tools")
        else:
            with tarfile.open(archive) as source: source.extractall(ROOT / ".tools", filter="data")
        binaries[name] = ROOT / ".tools" / folder / (name + (".exe" if system == "Windows" else ""))
    binaries["adapter"] = download("wasi_snapshot_preview1.command.wasm",
        "https://github.com/bytecodealliance/wasmtime/releases/download/v50.0.1/wasi_snapshot_preview1.command.wasm", ADAPTER_SHA)
    archive = download("wasi-http-v0.2.0.tar.gz", "https://codeload.github.com/WebAssembly/wasi-http/tar.gz/refs/tags/v0.2.0", WIT_SHA)
    wit = directory / "wit"
    wit.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            if not member.isfile() or "/wit/" not in member.name or not member.name.endswith(".wit"): continue
            relative = member.name.split("/wit/", 1)[1]
            destination = wit / ("deps/http/" + relative if "/" not in relative else relative)
            if not destination.resolve().is_relative_to(wit.resolve()): raise RuntimeError("Invalid WIT path")
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source.extractfile(member).read())
    shutil.copyfile(ROOT / "http/probe.wit", wit / "probe.wit")
    return binaries, wit
