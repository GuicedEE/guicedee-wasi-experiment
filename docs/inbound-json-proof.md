# One inbound JSON route in WASI HTTP

`POST /calculate` accepts a JSON object containing exactly one signed 32-bit
integer named `value`. Guice constructs the Java handler and injects its
calculator, which receives a bound Java limit object. The calculator clamps to
[-100, 100], doubles the clamped integer, and returns the applied value and a
clamp flag. Jackson core parses the bounded JSON payload in the guest.

| Request | Status | Response |
| --- | --- | --- |
| `{"value":21}` | 200 | `{"result":42,"used":21,"clamped":false}` |
| `{"value":305}` | 200 | `{"result":200,"used":100,"clamped":true}` |
| `{"value":-305}` | 200 | `{"result":-200,"used":-100,"clamped":true}` |
| Empty, malformed, or over-depth JSON | 400 | `{"error":"malformed_json"}` |
| Missing, additional, duplicate, non-integer, or out-of-range field | 422 | `{"error":"invalid_input"}` |
| Body exceeds 256 bytes | 413 | `{"error":"body_too_large","max_bytes":256}` |
| Different path or a query on the route | 404 | `{"error":"not_found"}` |
| Different method on the route | 405 | `{"error":"method_not_allowed"}`, with `Allow: POST` |
| Other or missing content type | 415 | `{"error":"unsupported_media_type"}` |

The bridge accepts `application/json` and its `; charset=utf-8` form
case-insensitively. All fixture responses carry `Content-Type: application/json`.
The C transport checks the route, method and media type before reading the
request. It copies at most 256 body bytes, reading one extra byte to detect
overflow even with chunked transfer. Java receives the bytes only after the
size check. Jackson is configured with a depth limit of 16. A body stream
failure maps to 400 and an incomplete Java dispatch maps to 500.

## Reactor and build provenance

The source is based on standalone pin
`20fd850da94e8eadff9b36d4042bf25dede3bfc4`. The checked TeaVM reactor
adaptation, WIT and response-writing architecture derive from the earlier
inbound proof at `5f9b6c4f9249a7fbfa3922cbb2cadeb270dd7cc7`. Initialization
runs once per Wasm instance. For each accepted request the reactor starts
Java's main fiber and verifies the queue drains. Guice resolves the static
handler on first Java dispatch. The proof exercises sequential dispatch and
recovery in the same instance.

The build uses Java 25, TeaVM 0.16.0 compiler backport revision 3, the checked
Jackson 3.3.0 profile, wasi-sdk 34, wit-bindgen 0.63.0, wasm-tools 1.263.0, and
Wasmtime 50.0.1. It shares the [published inputs and checked build
recipes](standalone-wasi-proof.md#external-inputs) with typed outbound HTTP.
The SDK links a Preview 1 reactor adapter. The component exports
`wasi:http/incoming-handler@0.2.0`. The verification rejects guest socket
imports, an outbound HTTP handler import, and a command export.

Wasmtime listens on `127.0.0.1:0` for the smoke run. The host supplies WASI
CLI support required by the adapter, with no guest filesystem preopens,
environment inheritance, DNS lookup or guest TCP/UDP. The fixture uses one
HTTP connection and checks a per-instance marker on every response.
Concurrent dispatch and production framework lifecycle are outside this proof.

## Reproduce

Run from a fresh Linux x86-64 export with Bash, Git, tar, Python 3.12 or newer
and JDK 25 on `PATH`. Public Maven and tool downloads follow the existing
hash-checked recipes. The raw HTTP gate warms the isolated Maven cache for the
compiler backport.

```bash
set -euo pipefail
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/guicedee-wasi-json.XXXXXX")
git archive HEAD | tar -xf - -C "$run_dir"
cd "$run_dir"
unset MAVEN_ARGS MAVEN_OPTS WASMTIME WASI_SDK_PATH MVNW_REPOURL
ulimit -Ss 32768
python3 scripts/run_probe.py jvm
python3 scripts/run_probe.py c-http
python3 scripts/run_probe.py c-inbound-json
```

The inbound artifact is `target/c/c-inbound-json/inbound.wasm`. The emitted
component interface, exact wire observations, tool logs, and result manifest
are in `target/evidence/c-inbound-json/`. Use the wire assertions to compare
runtime behaviour across fresh builds. The pinned compiler can vary its
generated proxy code and thus the Wasm hash.

## Verified on Angeloft

The clean export passed 14 JVM tests, the raw outbound regression, and 18
loopback response checks for this reactor. The fixture accepted the 256-byte
boundary and rejected 257 bytes using both Content-Length and chunked
transfer. All rejection cases shared the instance with the final successful
request, and the host shut down cleanly.

- Wasm SHA-256 `4b1de4c4a2bbb7f85a7be6f7449e725bb930c14a6e514963939b991e016fc1cc`
- Wire observations SHA-256 `54e2dec15de9b406ff0e4afb7c0f9d9abe0c1aac5a69a1ff1d6b1096c75ed473`

A separate build also passed the typed outbound regression, with 150 verified
requests and both resource-exhaustion controls.
