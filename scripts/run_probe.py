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
    "c-http": ("HttpProbe", "WASI_HTTP_OK get post status transport timeout bounded-body cleanup"),
    "c-typed-http": ("TypedHttpProbe", "WASI_TYPED_HTTP_OK jackson3 real-guice get post errors cleanup"),
    "c-guice-scopes": ("GuiceScopeProbe", "WASI_GUICE_SCOPES_OK providers singleton scope cleanup failures isolation"),
    "c-guice-metadata": ("GuiceMinimalProbe", "WASI_GUICE_MINIMAL_OK constructor"),
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
    if probe in ("c-http", "c-typed-http"):
        from run_http_probe import run as run_http
        return run_http(typed=probe == "c-typed-http")
    evidence = ROOT / "target" / "evidence" / probe
    evidence.mkdir(parents=True, exist_ok=True)
    # A failed compile must not leave an earlier successful runtime log looking current.
    for stale in ("missing-apis.txt", "execute-wasi.log", "compile-wasm.log"):
        (evidence / stale).unlink(missing_ok=True)
    result = {"probe": probe, "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "status": "FAIL", "steps": [], "http_component_tested": False}

    def step(name, command, timeout=900, env=None):
        start = time.monotonic()
        print(f"{probe}: {name}", flush=True)
        log = evidence / f"{name}.log"
        with log.open("w", encoding="utf-8") as output:
            try:
                code = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=output,
                                      stderr=subprocess.STDOUT, timeout=timeout, env=env).returncode
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
            injector = probe in ("c-guice-metadata", "c-guice-scopes")
            compatibility = injector or probe in ("c-reflection", "c-guice-compat", "c-guice-classlib")
            classlib = injector or probe in ("c-concurrency", "c-guice-classlib")
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
            if injector:
                result["metadata_extension"] = True
                code, _ = step("build-metadata", [*maven, "-f", "metadata/pom.xml", "-DskipTests", "install"])
                if code: raise RuntimeError("Metadata extension failed to build")
                profiles.append("metadata-compat")
                revision = 2
                version = f"0.16.0-guicedee-wasi-{revision}"
                result["compiler_backport"] = version
                code, _ = step("build-compiler-fix", [sys.executable, "scripts/build_teavm_fix.py", "--revision", revision])
                if code: raise RuntimeError("Compiler backport failed to build")
                code, _ = step("install-compiler-fix", [*maven, "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
                    f"-Dfile=target/compiler-fix/teavm-core-{version}.jar", "-DpomFile=target/compiler-fix/pom.xml"])
                if code: raise RuntimeError("Compiler backport failed to install")
                result["compiler_backport_details"] = json.loads((ROOT / "target/compiler-fix/backport.json").read_text())
                profiles.append("compiler-fix")
                maven.append(f"-Dcompiler.fix.version={version}")
            extra = ["-Dteavm.version=0.16.0", "-Dprobe.targetType=C", f"-Dprobe.outputDirectory={directory}"] if c_backend else []
            if injector:
                extra.append("-e")
            compiler_env = None
            if injector:
                compiler_env = os.environ.copy()
                compiler_env["MAVEN_OPTS"] = compiler_env.get("MAVEN_OPTS", "") + " -Xmx4g -Xss16m"
                result["compiler_max_heap_mib"] = 4096
            code, compile_log = step("compile-c" if c_backend else "compile-wasi",
                                    [*maven, "-P" + ",".join(profiles), "-DskipTests", f"-Dprobe={probe}",
                                     f"-Dprobe.mainClass=com.guicedee.wasi.experiment.{main}", *extra, "process-classes"], env=compiler_env)
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
                optimization = "-O0" if injector else "-O1"
                result["c_optimization"] = optimization
                stack_options = []
                if injector:
                    result["c_stack_bytes"] = 4 * 1024 * 1024
                    stack_options = ["-Wl,-z,stack-size=4194304"]
                code, _ = step("compile-wasm", [clang, f"--sysroot={sdk / 'share/wasi-sysroot'}", optimization,
                                               "-mllvm", "-wasm-enable-sjlj", "-mllvm", "-wasm-use-legacy-eh=false",
                                               "-mexception-handling", "-Wno-incompatible-pointer-types", "-Wno-int-conversion", "-Wno-parentheses-equality",
                                               directory / "wasi_all.c", "-lsetjmp", "-lm", *stack_options, "-o", artifact])
                if code: result["failed_gate"] = "C to WASI compilation"
            if code == 0:
                result["wasm_bytes"] = artifact.stat().st_size
                result["wasm_sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
                arguments = ["--wasi-limits"] if probe == "c-concurrency" else []
                code, log = step("execute-wasi", [runtime, "run", "-W", "exceptions=y", "-W", "timeout=30s", artifact, *arguments], timeout=60)
                result["marker_found"] = expected in log.read_text(encoding="utf-8", errors="replace").splitlines()
                if probe == "c-guice-scopes":
                    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
                    checkpoints = ["WASI_GUICE_PROVIDERS_SINGLETON_OK", "WASI_GUICE_SCOPE_LIFECYCLE_OK",
                                   "WASI_GUICE_PROVISION_FAILURES_OK", "WASI_GUICE_CLEANUP_FAILURES_OK",
                                   "WASI_GUICE_SCOPE_THREADS_OK"]
                    result["checkpoints"] = {marker: marker in lines for marker in checkpoints}
                    result["marker_found"] = result["marker_found"] and all(result["checkpoints"].values())
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
