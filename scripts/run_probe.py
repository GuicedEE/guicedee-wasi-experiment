"""Run one real gate; preserve compiler/runtime failures and return a failing exit code."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from wasmtime_tool import ROOT, install

PROBES = {
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
            runtime = install()
            code, _ = step("wasmtime-version", [runtime, "--version"])
            if code:
                raise RuntimeError("Wasmtime did not start")
            main, expected = PROBES[probe]
            artifact = ROOT / "target/wasi" / probe / f"{probe}.wasm"
            artifact.unlink(missing_ok=True)
            code, _ = step("compile-wasi", [*maven, "-Pwasi", "-DskipTests", f"-Dprobe={probe}",
                                            f"-Dprobe.mainClass=com.guicedee.wasi.experiment.{main}", "process-classes"])
            if code == 0:
                result["wasm_bytes"] = artifact.stat().st_size
                result["wasm_sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
                code, log = step("execute-wasi", [runtime, "run", "-W", "timeout=30s", artifact], timeout=60)
                result["marker_found"] = expected in log.read_text(encoding="utf-8", errors="replace").splitlines()
                result["status"] = "PASS" if code == 0 and result["marker_found"] else "FAIL"
                result["failed_gate"] = None if result["status"] == "PASS" else "WASI execution/output"
            else:
                result["failed_gate"] = "WASI compilation"
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
