"""Run one real gate; preserve compiler/runtime failures and return a failing exit code."""
import argparse
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
from wasmtime_tool import ROOT, install
from wasi_sdk_tool import install as install_sdk
from prepare_c import prepare

PROBES = {
    "c-concurrency": ("ConcurrencyProbe", "WASI_CONCURRENCY_OK queue identity-cas update-retry lock-owner cooperative-thread"),
    "c-guice-classlib": ("GuiceMinimalProbe", "WASI_GUICE_MINIMAL_OK constructor"),
    "c-baseline": ("BaselineProbe", "WASI_BASELINE_OK sum=42 exception=caught"),
    "c-runtime": ("RuntimeProbe", "WASI_RUNTIME_OK heap-gc array-clone timer"),
    "c-reflection": ("ReflectionProbe", "WASI_REFLECTION_OK constructor annotation generic-field method"),
    "c-guice-minimal": ("GuiceMinimalProbe", "WASI_GUICE_MINIMAL_OK constructor"),
    "c-guice-compat": ("GuiceMinimalProbe", "WASI_GUICE_MINIMAL_OK constructor"),
    "guice-minimal": ("GuiceMinimalProbe", "WASI_GUICE_MINIMAL_OK constructor"),
    "baseline": ("BaselineProbe", "WASI_BASELINE_OK sum=42 exception=caught"),
    "guice": ("GuiceProbe", "WASI_GUICE_OK constructor provider singleton scope interceptor"),
}


def run(probe):
    evidence = ROOT / "target" / "evidence" / probe
    evidence.mkdir(parents=True, exist_ok=True)
    result = {"probe": probe, "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "status": "FAIL", "steps": [], "http_component_tested": False}

    def step(name, command, timeout=900):
        start = time.monotonic()
        print(f"{probe}: {name}", flush=True)
        log = evidence / f"{name}.log"
        with log.open("w", encoding="utf-8") as output:
            try:
                code = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=output,
                                      stderr=subprocess.STDOUT, timeout=timeout).returncode
            except subprocess.TimeoutExpired:
                output.write("\nPROCESS TIMEOUT\n")
                code = 124
        result["steps"].append({"name": name, "exit_code": code,
                                "elapsed_seconds": round(time.monotonic() - start, 3), "log": log.name})
        print(f"{probe}: {name} exit={code}", flush=True)
        if code:
            print("\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-30:]))
        return code, log

    try:
        step("java-version", ["java", "-version"])
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        maven = [wrapper, "-B", "-ntp", "-s", ".mvn/experiment-settings.xml", "-gs",
                 ".mvn/experiment-settings.xml", "-Dmaven.repo.local=" + str(ROOT / ".cache/m2")]
        step("maven-version", [wrapper, "-version"])
        if probe == "jvm":
            code, _ = step("jvm-tests", [*maven, "test"])
            result["status"] = "PASS" if code == 0 else "FAIL"
        else:
            c_backend = probe.startswith("c-")
            compatibility = probe in ("c-reflection", "c-guice-compat", "c-guice-classlib")
            classlib = probe in ("c-concurrency", "c-guice-classlib")
            result["compiler_backend"] = "TeaVM 0.16 C + wasi-sdk 34" if c_backend else "TeaVM 0.13 WASI"
            result["compatibility_extension"] = compatibility
            result["classlib_extension"] = classlib
            runtime = install()
            code, _ = step("wasmtime-version", [runtime, "--version"])
            if code:
                raise RuntimeError("Wasmtime did not start")
            main, expected = PROBES[probe]
            directory = ROOT / ("target/c" if c_backend else "target/wasi") / probe
            artifact = directory / f"{probe}.wasm"
            artifact.unlink(missing_ok=True)
            profiles = ["wasi"]
            if classlib:
                code, _ = step("build-classlib", [*maven, "-f", "classlib/pom.xml", "-DskipTests", "install"])
                if code: raise RuntimeError("Class-library extension failed to build")
                profiles.append("classlib-compat")
            if compatibility:
                code, _ = step("build-compatibility", [*maven, "-f", "compatibility/pom.xml", "-DskipTests", "install"])
                if code: raise RuntimeError("Compiler extension failed to build")
                profiles.append("guice-compat")
            extra = ["-Dteavm.version=0.16.0", "-Dprobe.targetType=C", f"-Dprobe.outputDirectory={directory}"] if c_backend else []
            code, compile_log = step("compile-c" if c_backend else "compile-wasi",
                                    [*maven, "-P" + ",".join(profiles), "-DskipTests", f"-Dprobe={probe}",
                                     f"-Dprobe.mainClass=com.guicedee.wasi.experiment.{main}", *extra, "process-classes"])
            if code:
                result["failed_gate"] = "Java to C compilation" if c_backend else "WASI compilation"
                missing = list(dict.fromkeys(re.findall(r"^\[ERROR\] (?:Class|Method|Field) .+",
                                                        compile_log.read_text(encoding="utf-8", errors="replace"), re.M)))
                result["missing_api_diagnostics"] = len(missing)
                (evidence / "missing-apis.txt").write_text("\n".join(missing) + "\n", encoding="utf-8")
            if code == 0 and c_backend:
                sdk = install_sdk()
                clang = sdk / "bin" / ("clang.exe" if os.name == "nt" else "clang")
                step("clang-version", [clang, "--version"])
                prepare(directory)
                code, _ = step("compile-wasm", [clang, f"--sysroot={sdk / 'share/wasi-sysroot'}", "-O1",
                                               "-mllvm", "-wasm-enable-sjlj", "-mllvm", "-wasm-use-legacy-eh=false",
                                               "-mexception-handling", "-Wno-incompatible-pointer-types", "-Wno-int-conversion",
                                               directory / "wasi_all.c", "-lsetjmp", "-lm", "-o", artifact])
                if code: result["failed_gate"] = "C to WASI compilation"
            if code == 0:
                result["wasm_bytes"] = artifact.stat().st_size
                result["wasm_sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
                arguments = ["--wasi-limits"] if probe == "c-concurrency" else []
                code, log = step("execute-wasi", [runtime, "run", "-W", "exceptions=y", "-W", "timeout=30s", artifact, *arguments], timeout=60)
                result["marker_found"] = expected in log.read_text(encoding="utf-8", errors="replace").splitlines()
                if probe == "c-concurrency":
                    result["limits_marker_found"] = "WASI_CONCURRENCY_LIMITS_OK blocking timed condition fair rejected" in log.read_text(encoding="utf-8", errors="replace").splitlines()
                    result["marker_found"] = result["marker_found"] and result["limits_marker_found"]
                result["status"] = "PASS" if code == 0 and result["marker_found"] else "FAIL"
                result["failed_gate"] = None if result["status"] == "PASS" else "WASI execution/output"
    except Exception as error:
        result["error"] = str(error)
    finally:
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("probe", choices=["jvm", *PROBES])
    sys.exit(run(parser.parse_args().probe))
