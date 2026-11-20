"""Build and verify the standalone inbound JSON reactor on Linux x86-64."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import resource
import subprocess
import sys

from http_tools import prepare_tools
from prepare_c import prepare
from prepare_reactor import prepare_reactor
from run_http_probe import prepare_typed_build
from wasi_sdk_tool import install as install_sdk
from wasmtime_tool import ROOT, install


def run():
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("The inbound JSON gate is verified on Linux x86-64")
    evidence = ROOT / "target/evidence/c-inbound-json"
    directory = ROOT / "target/c/c-inbound-json"
    evidence.mkdir(parents=True, exist_ok=True)
    directory.mkdir(parents=True, exist_ok=True)
    for old in evidence.iterdir():
        if old.is_file():
            old.unlink()
    artifact = directory / "inbound.wasm"
    artifact.unlink(missing_ok=True)
    result = {"probe": "c-inbound-json", "status": "FAIL", "steps": []}

    def step(name, command, timeout=600, env=None):
        print(f"c-inbound-json: {name}", flush=True)
        log = evidence / f"{name}.log"
        with log.open("w", encoding="utf-8") as output:
            try:
                status = subprocess.run([str(part) for part in command], cwd=ROOT,
                                        stdout=output, stderr=subprocess.STDOUT, timeout=timeout,
                                        env=env).returncode
            except subprocess.TimeoutExpired:
                output.write("\nPROCESS TIMEOUT\n")
                status = 124
        result["steps"].append({"name": name, "exit_code": status, "log": log.name})
        content = log.read_text(encoding="utf-8", errors="replace")
        if status:
            print(content[-7000:])
            raise RuntimeError(f"{name} failed with exit {status}")
        return content

    try:
        binaries, wit = prepare_tools(directory, world="inbound.wit")
        sdk, runtime = install_sdk(), install()
        clang = sdk / "bin/clang"
        for name, executable in (("java", "java"), ("clang", clang), ("wasmtime", runtime),
                                 ("wasm-tools", binaries["wasm-tools"]), ("wit-bindgen", binaries["wit-bindgen"])):
            step(f"{name}-version", [executable, "-version" if name == "java" else "--version"])

        maven = [ROOT / "mvnw", "-B", "-ntp", "-s", ".mvn/experiment-settings.xml",
                 "-gs", ".mvn/experiment-settings.xml", f"-Dmaven.repo.local={ROOT / '.cache/m2'}"]
        profiles = prepare_typed_build(maven, step, result)
        step("compile-c", [*maven, "-e", f"-P{profiles}", "-DskipTests", "-Dteavm.version=0.16.0",
             "-Dprobe=c-inbound-json", "-Dprobe.mainClass=com.guicedee.wasi.experiment.InboundCalculation",
             "-Dprobe.targetType=C", f"-Dprobe.outputDirectory={directory}", "process-classes"],
             env={**os.environ, "MAVEN_OPTS": os.environ.get("MAVEN_OPTS", "") + " -Xmx8g -Xss16m"})
        prepare(directory)
        prepare_reactor(directory)

        bindings = directory / "bindings"
        step("generate-bindings", [binaries["wit-bindgen"], "c", "--world", "inbound",
                                  "--out-dir", bindings, wit])
        soft, hard = resource.getrlimit(resource.RLIMIT_STACK)
        if soft != resource.RLIM_INFINITY and soft < 32 * 1024 * 1024:
            resource.setrlimit(resource.RLIMIT_STACK, (32 * 1024 * 1024, hard))
        compiler = [clang, "--target=wasm32-wasip1", "-O0", "-mllvm", "-wasm-enable-sjlj",
                    "-mllvm", "-wasm-use-legacy-eh=false", "-mexception-handling",
                    "-Wno-incompatible-pointer-types", "-Wno-int-conversion",
                    "-Wno-parentheses-equality", "-I", ROOT / "runtime", "-I", bindings]
        objects = []
        for name, source in (("guest", directory / "wasi_all.c"),
                             ("handler", ROOT / "runtime/wasi_inbound_handler.c"),
                             ("bindings", bindings / "inbound.c")):
            obj = directory / f"{name}.o"
            step(f"compile-{name}", [*compiler, "-c", source, "-o", obj])
            objects.append(obj)
        syslib = sdk / "share/wasi-sysroot/lib/wasm32-wasip1"
        builtins = step("builtins", [clang, "--target=wasm32-wasip1", "-print-libgcc-file-name"]).strip()
        step("link-component", [sdk / "bin/wasm-component-ld", "--wasm-ld-path", sdk / "bin/wasm-ld",
             "--wasi-adapter", "reactor", "-m", "wasm32", f"-L{syslib / 'noeh'}", f"-L{syslib}",
             syslib / "crt1-reactor.o", "--entry", "_initialize", *objects,
             bindings / "inbound_component_type.o", "-lsetjmp", "-lm", "-lc", builtins,
             "-z", "stack-size=4194304", "--strip-all", "-o", artifact])
        step("validate", [binaries["wasm-tools"], "validate", artifact])
        world = step("component-wit", [binaries["wasm-tools"], "component", "wit", artifact])
        if "export wasi:http/incoming-handler@0.2.0;" not in world or re.search(
                r"import wasi:(sockets/|http/outgoing-handler)|export wasi:cli/run", world):
            raise RuntimeError("Unexpected component capability surface")
        step("loopback", [sys.executable, ROOT / "scripts/inbound_json_fixture.py",
                         runtime, artifact, evidence / "wire.json"], timeout=60)
        wire = json.loads((evidence / "wire.json").read_text())
        result["wire_checks"] = len(wire)
        result["wasm_sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        result["wasm_bytes"] = artifact.stat().st_size
        result["status"] = "PASS"
    except Exception as failure:
        result["error"] = str(failure)
    finally:
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(f"c-inbound-json: {result['status']}", flush=True)
    return 0 if result["status"] == "PASS" else 1
