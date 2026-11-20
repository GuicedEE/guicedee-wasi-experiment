"""Assert the component's complete JSON responses on a loopback listener."""
import argparse
import http.client
import json
from pathlib import Path
import re
import select
import signal
import subprocess


def probe(wasmtime, artifact):
    command = [
        wasmtime, "serve", "--config", "/dev/null", "--addr", "127.0.0.1:0",
        "-W", "exceptions=y,timeout=10s", "--max-instance-reuse-count", "64",
        "--idle-instance-timeout", "1s",
        "-S", "cli=y,inherit-network=n,tcp=n,udp=n,allow-ip-name-lookup=n,inherit-env=n",
        artifact,
    ]
    small = b'{"value":21}'
    success = '{"result":42,"used":21,"clamped":false}'
    clamped = '{"result":200,"used":100,"clamped":true}'
    malformed = '{"error":"malformed_json"}'
    invalid = '{"error":"invalid_input"}'
    too_large = '{"error":"body_too_large","max_bytes":256}'
    max_body = small.ljust(256)
    cases = [
        ("ordinary", "POST", "/calculate", small, 200, success),
        ("clamped", "POST", "/calculate", b'{"value":305}', 200, clamped),
        ("negative-clamp", "POST", "/calculate", b'{"value":-305}', 200,
         '{"result":-200,"used":-100,"clamped":true}'),
        ("charset", "POST", "/calculate", small, 200, success),
        ("malformed", "POST", "/calculate", b'{"value":', 400, malformed),
        ("missing-value", "POST", "/calculate", b'{}', 422, invalid),
        ("depth-limit", "POST", "/calculate", b'{"value":' + b'[' * 17 + b'0' + b']' * 17 + b'}', 400, malformed),
        ("invalid-type", "POST", "/calculate", b'{"value":"21"}', 422, invalid),
        ("duplicate", "POST", "/calculate", b'{"value":21,"value":22}', 422, invalid),
        ("numeric-overflow", "POST", "/calculate", b'{"value":2147483648}', 422, invalid),
        ("body-limit", "POST", "/calculate", max_body, 200, success),
        ("oversize", "POST", "/calculate", max_body + b" ", 413, too_large),
        ("chunked-oversize", "POST", "/calculate", (max_body, b" "), 413, too_large),
        ("wrong-method", "GET", "/calculate", None, 405, '{"error":"method_not_allowed"}'),
        ("wrong-route", "POST", "/missing", small, 404, '{"error":"not_found"}'),
        ("query-rejected", "POST", "/calculate?x=1", small, 404, '{"error":"not_found"}'),
        ("wrong-media-type", "POST", "/calculate", small, 415, '{"error":"unsupported_media_type"}'),
        ("recovery", "POST", "/calculate", small, 200, success),
    ]
    observations = []
    with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, env={"PATH": "/usr/bin:/bin"}) as process:
        try:
            if not select.select([process.stdout], [], [], 15)[0]:
                raise RuntimeError("Wasmtime startup exceeded 15 seconds")
            startup = process.stdout.readline().decode()
            address = re.fullmatch(r"Serving HTTP on http://127\.0\.0\.1:(\d+)/\s*", startup)
            if address is None:
                raise RuntimeError(f"Unexpected Wasmtime startup output: {startup!r}")
            connection = http.client.HTTPConnection("127.0.0.1", int(address[1]), timeout=12)
            try:
                for index, (label, method, path, body, status, expected) in enumerate(cases):
                    content_type = {
                        "wrong-media-type": "text/plain",
                        "charset": "application/json; charset=UTF-8",
                    }.get(label, "application/json")
                    headers = {"Content-Type": content_type}
                    chunked = isinstance(body, tuple)
                    connection.request(method, path, body, headers, encode_chunked=chunked)
                    response = connection.getresponse()
                    raw = response.read()
                    actual = (response.status, response.getheader("content-type"),
                              response.getheader("allow"), response.getheader("x-fixture-instance"), raw)
                    want = (status, "application/json", "POST" if status == 405 else None,
                            "first" if index == 0 else "reused", expected.encode())
                    if actual != want:
                        raise RuntimeError(f"{label} returned {actual!r}, expected {want!r}")
                    print(f"{label}: HTTP {status}", flush=True)
                    size = sum(map(len, body)) if chunked else len(body or b"")
                    observations.append({"case": label, "method": method, "path": path, "request_bytes": size,
                                         "chunked": chunked, "status": status, "content_type": actual[1],
                                         "allow": actual[2], "instance": actual[3], "body": raw.decode()})
            finally:
                connection.close()
        finally:
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
            try:
                output, _ = process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise RuntimeError("Wasmtime did not shut down within ten seconds")
            if process.returncode or b"error:" in output.lower():
                raise RuntimeError(f"Wasmtime exit {process.returncode}, output {output.decode()!r}")
    return observations


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wasmtime", type=Path)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    observations = probe(args.wasmtime.resolve(), args.artifact.resolve())
    args.output.write_text(json.dumps(observations, indent=2) + "\n")
    print(f"{len(observations)} verified loopback responses")
