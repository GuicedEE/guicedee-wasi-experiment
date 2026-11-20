# Standalone Guice and outbound WASI proof

This experiment exercises the published GuicedEE Guice 2.5.1 artifact in
Wasmtime. The scope runner checks providers, singleton identity, custom request
scope, cleanup on failures, and overlapping cooperative requests. The HTTP
probes use separate fixtures bound to IPv4 loopback. The typed client uses an
experimental synchronous transport and a selected Jackson 3 DTO profile.
Inbound hosting, Vert.x lifecycle, interception in the reflection-only profile,
and the production reactive REST client remain unqualified.

## Bootstrap and run

The validated host was Linux x86-64 with Oracle JDK 25.0.5.1 and Python 3.15.2.
Use JDK 25, Python 3.12 or newer, Bash, Git, and tar. Select the JDK with
`JAVA_HOME` and put its `bin` directory on `PATH`. The typed compiler requests
an 8 GiB JVM heap, so allow room for the compiler and its native build.

From this pinned Git checkout, export the committed files to a fresh directory.
The 32 MiB soft host-stack limit gives the pinned clang optimizer room on Linux
hosts with an 8 MiB default. `c-http` warms the isolated Maven cache with stock
TeaVM before the Guice gates build their backport.

```bash
set -euo pipefail
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/guicedee-wasi-run.XXXXXX")
git archive HEAD | tar -xf - -C "$run_dir"
cd "$run_dir"
unset MAVEN_ARGS MAVEN_OPTS WASMTIME WASI_SDK_PATH MVNW_REPOURL
stack_limit=$(ulimit -Ss)
if [[ "$stack_limit" != unlimited ]] && (( stack_limit < 32768 )); then
  ulimit -Ss 32768
fi

for gate in jvm c-http c-guice-metadata c-guice-scopes c-typed-http c-reflection; do
  python3 scripts/run_probe.py "$gate"
done
```

Find the manifests and execution logs under
`$run_dir/target/evidence/<gate>/` and the Wasm under
`$run_dir/target/c/<gate>/`. Each passing gate checks its runtime markers. The
HTTP runners also validate component structure, verify server-observed wire
behaviour, and check two resource-exhaustion controls.

## External inputs

The first run needs network access to Maven Central and the public GitHub
release and archive endpoints used by the runners. The Maven wrapper pins
Maven 4.0.0-rc-7. Maven uses the checked-in empty settings and
`.cache/m2` in the run directory and resolves GuicedEE's published Guice
2.5.1 and its dependencies, including Guava 33.8.0-jre. These are public
prebuilt artifacts.

The TeaVM 0.16.0 backport recipe checks the published compiler binary and
source JAR, compiles the replacement source against stock TeaVM dependencies,
and installs the resulting experimental compiler JAR into the isolated build.
The Guice gates use revision 2 and typed HTTP uses revision 3.
The Jackson recipe checks the published Jackson 3.3.0 core and databind
binary and source JARs and annotations 2.22. It compiles the selected
adaptations and creates the matching experimental Jackson JARs in the run
directory. Class-library, reflection compatibility and metadata helpers build
from this checkout. The recipes use no local DevSuite artifacts.

The C route pins wasi-sdk 34 and Wasmtime 50.0.1. The HTTP components also use
wit-bindgen 0.63.0, wasm-tools 1.263.0, the WASI HTTP 0.2.0 WIT archive, and
Wasmtime's preview1 command adapter from release 50.0.1. The download scripts
verify these inputs against their pinned SHA-256 values.

## Verification

The scoped build passed 14 JVM tests and the five native gates below. The raw
component exercised 129 calls, including 120 verified requests and nine
deliberate connection refusals. The typed component passed five checkpoints
and the fixture verified 150 requests. Both deliberate resource-exhaustion
controls fired in each HTTP gate.

| Gate | SHA-256 from the scoped build | Comparison with the prior overlay verification |
| --- | --- | --- |
| `c-http` | `83c67d8163eb685623b222a523ad0a8a5024b58d6c6f3d06afb48e335de3b502` | Matched |
| `c-guice-metadata` | `646a0470ac0934882d113bb8a64ff2ddfb2d82c169492945be187a85cd7fc816` | Different |
| `c-guice-scopes` | `4f6d4372d0b68a855656038198861ea8b8f0a1baa253e901457384a127659001` | Different |
| `c-typed-http` | `7e8117831d55f0eb04e03bf46c81ec957500704ca16b40c3790a4c267ed738ff` | Different |
| `c-reflection` | `a9a3ae6e23d2317bb83830e36ac8a21aeb68a496199d54b3da8f0979b5b813e6` | Regression check |

The raw HTTP component reproduced byte for byte. The Guice and typed HTTP gates reproduced their runtime behaviour with different Wasm hashes.

## Source provenance

The source pin starts at private experiment revision
`5bad0752cf6c2afe4b893f248448cd9d9aceabd3`. The earlier successful owner
snapshot was inventoried in the 103-entry `SOURCE_INPUTS.sha256` with manifest
SHA-256 `df60d234291380e48a07640f3cdfd4dde51d3acf464c9ad21465034cdcb5f55c`.
The pin imports the hash-checked standalone sources. Focused edits trim
unrelated compiler revisions, render and filesystem branches, and standalone
fixture assertions from the shared harness and compatibility files.
