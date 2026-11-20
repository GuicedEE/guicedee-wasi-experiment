"""Build Java -> C -> WASI HTTP component and exercise actual loopback HTTP traffic."""
import base64
from collections import Counter
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http_tools import prepare_tools, RELEASES, ADAPTER_SHA, WIT_SHA
from prepare_c import prepare
from wasi_sdk_tool import install as install_sdk
from wasmtime_tool import ROOT, install

MARKERS = ["WASI_HTTP_GET_POST_OK", "WASI_HTTP_FAILURE_RECOVERY_OK",
           "WASI_HTTP_CLEANUP_OK calls=129", "WASI_HTTP_OK get post status transport timeout bounded-body cleanup"]
VARIANTS = {"http": [], "http-leak": ["-DHTTP_PROBE_LEAK=1"],
            "http-owned-options": ["-DHTTP_PROBE_OWNED_OPTIONS=1"]}

class Fixture:
    def __init__(self, capture=None):
        self.capture = capture
        self.token = secrets.token_hex(16)
        self.requests = []
        self.lock = threading.Lock()
        self.workers = set()
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def setup(self):
                super().setup()
                self.connection.settimeout(5)
            def log_message(self, *_): pass
            def do_GET(self): self.respond()
            def do_POST(self): self.respond()
            def respond(self):
                with fixture.lock: fixture.workers.add(threading.current_thread())
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                with fixture.lock:
                    fixture.requests.append({"method": self.command, "path": self.path,
                        "headers": dict(self.headers.items()), "body_base64": base64.b64encode(body).decode()})
                try:
                    if self.headers.get("x-probe-token") != fixture.token:
                        self.send_error(403); return
                    status, response = fixture.response(self.path, body)
                    if self.path == "/post": status, response = 201, body
                    elif self.path == "/empty": status, response = 204, b""
                    elif self.path.startswith("/status/"):
                        status = int(self.path.rsplit("/", 1)[1]); response = f"error-{status}".encode()
                    elif self.path == "/large": response = b"x" * 8192
                    elif self.path in ("/slow-body", "/truncated"): response = b"abcdefghij"
                    if self.path == "/slow-headers": time.sleep(0.8)
                    self.send_response(status)
                    self.send_header("Content-Length", str(len(response)))
                    self.send_header("x-probe-token", fixture.token)
                    self.send_header("Connection", "close")
                    self.end_headers()
                    if self.path in ("/slow-body", "/truncated"):
                        self.wfile.write(response[:2]); self.wfile.flush()
                        if self.path == "/truncated": return
                        time.sleep(0.8)
                        response = response[2:]
                    self.wfile.write(response); self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass  # Expected after guest deadline/size cancellation.
                finally:
                    self.close_connection = True
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05})
        # A reserved, non-listening port avoids a bind/release race in the refusal case.
        self.refused = socket.socket()
        self.refused.bind(("127.0.0.1", 0))

    def __enter__(self): self.thread.start(); return self
    def __exit__(self, *_):
        self.server.shutdown(); self.thread.join(3); self.server.server_close(); self.refused.close()
        with self.lock: workers = list(self.workers)
        for worker in workers: worker.join(3)
        if self.capture:
            self.capture.write_text(json.dumps(self.requests, indent=2) + "\n", encoding="utf-8")
        if self.thread.is_alive() or any(w.is_alive() for w in workers): raise RuntimeError("Fixture failed to stop")

    def args(self):
        return [f"127.0.0.1:{self.server.server_port}", f"127.0.0.1:{self.refused.getsockname()[1]}", self.token]

    def response(self, path, body):
        return 200, ("hello-" + self.token).encode()

    def verify(self):
        counts = Counter(row["path"] for row in self.requests)
        expected = {"/get?name=a%20b&n=42": 64, "/post": 1, "/empty": 1,
                    **{path: 9 for path in ("/status/404", "/status/500", "/slow-headers", "/slow-body", "/large", "/truncated")}}
        if dict(counts) != expected: raise RuntimeError(f"Server-observed requests differ: {counts}")
        for row in self.requests:
            headers = {k.lower(): v for k, v in row["headers"].items()}
            if headers.get("x-probe-token") != self.token: raise RuntimeError("Header did not reach server")
            expected_body = ('{"message":"WASI café ☕","token":"' + self.token + '"}').encode() if row["path"] == "/post" else b""
            if base64.b64decode(row["body_base64"]) != expected_body: raise RuntimeError("Wire body mismatch")
            if row["method"] != ("POST" if row["path"] == "/post" else "GET"): raise RuntimeError("Wire method mismatch")
        return dict(counts)

def run(typed=False):
    probe = "c-typed-http" if typed else "c-http"
    if typed:
        from typed_http_fixture import TypedFixture, TYPED_MARKERS
    fixture_type = TypedFixture if typed else Fixture
    markers = TYPED_MARKERS if typed else MARKERS
    directory = ROOT / "target/c" / probe
    evidence = ROOT / "target/evidence" / probe
    directory.mkdir(parents=True, exist_ok=True); evidence.mkdir(parents=True, exist_ok=True)
    # Replace evidence on each invocation; never leave a previous passing result current.
    for old in evidence.glob("*.log"): old.unlink()
    for old in evidence.glob("*.json"): old.unlink()
    result = {"probe": probe, "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "status": "FAIL", "http_component_tested": False, "steps": [], "deployment": False}
    def step(name, command, timeout=600, expect_failure=False, env=None):
        print(probe + ":", name, flush=True)
        start = time.monotonic()
        with (evidence / (name + ".log")).open("w", encoding="utf-8") as output:
            try: code = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=output,
                                      stderr=subprocess.STDOUT, timeout=timeout, env=env).returncode
            except subprocess.TimeoutExpired: code = 124; output.write("\nPROCESS TIMEOUT\n")
        result["steps"].append({"name": name, "exit_code": code, "seconds": round(time.monotonic()-start, 3)})
        log = (evidence / (name + ".log")).read_text(encoding="utf-8", errors="replace")
        if (code != 0 and not expect_failure) or code == 124:
            print(log[-7000:]); raise RuntimeError(f"{name} failed: {code}")
        return code, log
    try:
        binaries, wit = prepare_tools(directory)
        runtime, sdk = install(), install_sdk()
        clang = sdk / "bin" / ("clang.exe" if os.name == "nt" else "clang")
        for name, command in (("java-version", ["java", "-version"]), ("wasmtime-version", [runtime, "--version"]),
                ("clang-version", [clang, "--version"]), ("wit-bindgen-version", [binaries["wit-bindgen"], "--version"]),
                ("wasm-tools-version", [binaries["wasm-tools"], "--version"])): step(name, command)
        result["component_inputs"] = {"tools": RELEASES, "adapter_version": "50.0.1", "adapter_sha256": ADAPTER_SHA,
                                      "wasi_http_wit_version": "0.2.0", "wit_archive_sha256": WIT_SHA}
        bindings = directory / "bindings"
        step("generate-bindings", [binaries["wit-bindgen"], "c", wit, "--out-dir", bindings])
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        maven = [wrapper, "-B", "-ntp", "-s", ".mvn/experiment-settings.xml", "-gs", ".mvn/experiment-settings.xml",
                 "-Dmaven.repo.local=" + str(ROOT / ".cache/m2")]
        profiles, main, compiler_env = "wasi", "HttpProbe", None
        if typed:
            from build_jackson_profile import VERSION
            step("build-jackson-profile", [sys.executable, "scripts/build_jackson_profile.py"])
            for artifact_name in ("jackson-core", "jackson-databind"):
                step("install-" + artifact_name, [*maven, "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
                    f"-Dfile=target/jackson-profile/{artifact_name}-{VERSION}.jar", f"-DpomFile=target/jackson-profile/{artifact_name}.pom"])
            maven.append(f"-Djackson.version={VERSION}")
            maven.append("-Dguicedee.wasi.typed=true")
            result["jackson_profile"] = json.loads((ROOT / "target/jackson-profile/profile.json").read_text())
            for module in ("classlib", "compatibility", "metadata"):
                step("build-" + module, [*maven, "-f", module + "/pom.xml", "-DskipTests", "install"])
            revision = 3
            compiler_version = f"0.16.0-guicedee-wasi-{revision}"
            step("build-compiler-fix", [sys.executable, "scripts/build_teavm_fix.py", "--revision", revision])
            step("install-compiler-fix", [*maven, "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
                f"-Dfile=target/compiler-fix/teavm-core-{compiler_version}.jar", "-DpomFile=target/compiler-fix/pom.xml"])
            maven.append(f"-Dcompiler.fix.version={compiler_version}")
            result["compiler_backport_details"] = json.loads((ROOT / "target/compiler-fix/backport.json").read_text())
            profiles = "wasi,classlib-compat,guice-compat,metadata-compat,compiler-fix"
            main = "TypedHttpProbe"
            compiler_env = os.environ.copy()
            compiler_env["MAVEN_OPTS"] = compiler_env.get("MAVEN_OPTS", "") + " -Xmx8g -Xss16m"
            result["compiler_max_heap_mib"] = 8192
        step("compile-c", [*maven, "-e", "-P" + profiles, "-DskipTests", "-Dteavm.version=0.16.0", "-Dprobe=" + probe,
            "-Dprobe.mainClass=com.guicedee.wasi.experiment." + main, "-Dprobe.targetType=C",
            f"-Dprobe.outputDirectory={directory}", "process-classes"], env=compiler_env)
        prepare(directory)
        result["c_optimization"] = "-O0" if typed else "-O1"
        compiler_args = [clang, f"--sysroot={sdk / 'share/wasi-sysroot'}", result["c_optimization"], "-mllvm", "-wasm-enable-sjlj",
            "-mllvm", "-wasm-use-legacy-eh=false", "-mexception-handling", "-Wno-incompatible-pointer-types", "-Wno-parentheses-equality",
            "-Wno-int-conversion", "-I", ROOT / "runtime", "-I", bindings]
        # The Java guest and WIT bindings are identical in all three controls.
        # Compile once, then vary only the resource-owning HTTP bridge.
        guest_object, bindings_object = directory / "guest.o", directory / "bindings.o"
        step("compile-guest-object", [*compiler_args, "-c", directory / "wasi_all.c", "-o", guest_object])
        step("compile-bindings-object", [*compiler_args, "-c", bindings / "outbound_probe.c", "-o", bindings_object])
        compile_args = [*compiler_args, guest_object, ROOT / "runtime/wasi_http_probe.c", bindings_object,
            bindings / "outbound_probe_component_type.o", "-lsetjmp", "-lm",
            "-Wl,-z,stack-size=" + ("4194304" if typed else "1048576")]
        for variant, defines in VARIANTS.items():
            core, component = directory / (variant + "-core.wasm"), directory / (variant + ".wasm")
            core.unlink(missing_ok=True); component.unlink(missing_ok=True)
            step("compile-" + variant, [*compile_args, *defines, "-o", core])
            step("componentize-" + variant, [binaries["wasm-tools"], "component", "new", core, "--adapt",
                "wasi_snapshot_preview1=" + str(binaries["adapter"]), "-o", component])
            step("validate-" + variant, [binaries["wasm-tools"], "validate", component, "--features", "all"])
        _, world = step("component-wit", [binaries["wasm-tools"], "component", "wit", directory / "http.wasm"])
        if "wasi:http/outgoing-handler@0.2.0" not in world or "export wasi:cli/run" not in world:
            raise RuntimeError("Missing standard HTTP import or command export")
        result["expected_failure_controls"] = {}
        for variant in VARIANTS:
            artifact = directory / (variant + ".wasm")
            result[variant] = {"bytes": artifact.stat().st_size, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}
            with fixture_type(evidence / (variant + "-requests.json")) as fixture:
                code, log = step("execute-" + variant, [runtime, "run", "-W", "exceptions=y", "-W", "timeout=40s",
                    "-S", "http=y", "-S", "max-resources=64", "-S", "max-component-handles=64",
                    artifact, *fixture.args()], timeout=60, expect_failure=variant != "http")
                result[variant]["exit_code"] = code
                if variant == "http":
                    result["markers"] = {marker: marker in log.splitlines() for marker in markers}
                    if not all(result["markers"].values()): raise RuntimeError("Java success checkpoints missing")
                    result["server_request_counts"] = fixture.verify()
                    result["http_component_tested"] = True
                else:
                    diagnostic = log.lower()
                    detected = code != 0 and any(term in diagnostic for term in ("resource table", "resource limit", "resource count", "maximum concurrent", "too many", "handles limit", "handle limit"))
                    result["expected_failure_controls"][variant] = {"resource_exhaustion_detected": detected, "exit_code": code}
                    if not detected or markers[-1] in log: raise RuntimeError("Leak control did not fail for resource exhaustion")
        result["fixture_cleanup"] = "server threads joined and sockets closed"
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = str(error)
    finally:
        inputs = [ROOT / name for name in ("pom.xml", "src/main/java/module-info.java", "http/probe.wit",
            "src/main/java/com/guicedee/wasi/experiment/HttpProbe.java", "runtime/wasi_http_probe.c", "runtime/wasi_http_probe.h",
            "runtime/wasi_fiber.c", "runtime/wasi_properties.c", "runtime/wasi_memory.h", "scripts/run_http_probe.py",
            "scripts/http_tools.py", "scripts/prepare_c.py", "scripts/run_probe.py")]
        if typed:
            inputs += list((ROOT / "src/main/java").rglob("*.java"))
            for module in ("classlib", "compatibility", "metadata"):
                inputs += list((ROOT / module / "src").rglob("*.java"))
            inputs += [ROOT / "scripts/build_jackson_profile.py", ROOT / "scripts/typed_http_fixture.py", ROOT / "scripts/build_teavm_fix.py"]
        result["source_sha256"] = {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result["status"] == "PASS" else 1

if __name__ == "__main__": sys.exit(run())
