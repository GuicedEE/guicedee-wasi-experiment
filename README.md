# GuicedEE WASI experiment

Private, isolated feasibility tests for **Java 25 and actual Guice on standalone WASI**.
This is not a production runtime or a claim that GuicedEE services already run on WebAssembly.

**Current result:** the TeaVM 0.16 C → wasi-sdk route runs Java, selected reflection operations, heap/GC,
array cloning, timers, and a tested subset of queue/atomic/lock operations in standalone Wasmtime.
Actual Guice remains blocked: the new class-library profile reduces its missing-API diagnostics from 28 to 17.
See [the class-library continuation](docs/classlib-compatibility.md), [the C/WASI experiment](docs/c-wasi-compatibility.md)
and [the original baseline](docs/results-2026-11-15.md). The [Native Image assessment](docs/native-image-route.md)
also covers the existing executable, Web Image and LLVM-intermediate alternatives.

## Gates

| Probe | What it verifies | What it does not establish |
| --- | --- | --- |
| `jvm` | All six tests work on the JVM, including injection, scopes, interception, reflection, runtime and concurrency checks | WASI compatibility |
| `baseline` | Java 25 bytecode compiles to WASI and runs in Wasmtime: allocation, collections, exceptions and stdout | HTTP/component interfaces or Guice |
| `guice-minimal` | Constructor injection with no application interceptor or custom scope registration | Full Guice functionality |
| `guice` | The same Guice probe compiles and runs under WASI | Full GuicedEE bootstrap, Vert.x CallScoper or integrations |
| `c-baseline` | Original Java probe through TeaVM 0.16 C + wasi-sdk | Guice or HTTP |
| `c-runtime` | Heap growth/GC, independent array clones and a cooperative timer | OS threads or general scheduler compatibility |
| `c-reflection` | Reflective constructor/method invocation, constructor annotations and generic field metadata | Complete JDK reflection or Guice |
| `c-guice-minimal` | Unmodified Guice constructor probe through the new C route | Full framework compatibility |
| `c-guice-compat` | Real Guice with explicit reflection-only compiler substitutions | Interception, which this separate profile rejects |
| `c-concurrency` | FIFO queue/iterator behavior, identity CAS/retry, owner-aware reentrant locking across cooperative threads, and explicit unsupported-operation checks | Shared-memory threads, blocking contention or conditions |
| `c-guice-classlib` | Reflection-only Guice with the optional queue/atomic/lock library extension | Full Guice, HTTP or complete JDK concurrency |

Each gate returns nonzero on compilation failure, runtime failure, timeout, or missing success output.
The workflow keeps failed gates red and uploads their logs. A working baseline must not mask a failed Guice probe.

## Reproduce

Requires JDK 25 and Python 3.12+ on x86-64 Windows or Linux. The Maven wrapper downloads Maven 4.0.0-rc-7.
The script downloads Wasmtime 50.0.1 into `.tools` and checks the official release archive SHA-256.
Set `WASMTIME` to use another local executable; its actual version is captured in the evidence.

```text
python scripts/run_probe.py jvm
python scripts/run_probe.py baseline
python scripts/run_probe.py guice-minimal
python scripts/run_probe.py guice
python scripts/run_probe.py c-baseline
python scripts/run_probe.py c-runtime
python scripts/run_probe.py c-reflection
python scripts/run_probe.py c-guice-minimal
python scripts/run_probe.py c-guice-compat
python scripts/run_probe.py c-concurrency
python scripts/run_probe.py c-guice-classlib
```

Logs and machine-readable results are under `target/evidence/<probe>`; Wasm output is under `target/wasi/<probe>`.
C-route output is under `target/c/<probe>`; its pinned, checksum-verified wasi-sdk 34 is downloaded into `.tools`.
The SDK download is approximately 619 MB on Windows or 192 MB on Linux. Set `WASI_SDK_PATH` for an existing SDK.
Dependencies resolve from Maven Central through an isolated `.cache/m2` repository and an empty settings file,
so locally modified DevSuite artifacts and personal Maven repository settings cannot silently influence the baseline.

The equivalent compiler command is:

```text
./mvnw -B -ntp -s .mvn/experiment-settings.xml -gs .mvn/experiment-settings.xml -Dmaven.repo.local=.cache/m2 -Pwasi -DskipTests -Dprobe=baseline -Dprobe.mainClass=com.guicedee.wasi.experiment.BaselineProbe process-classes
```

Use `mvnw.cmd` on Windows. For Guice, use `-Dprobe=guice` and `-Dprobe.mainClass=com.guicedee.wasi.experiment.GuiceProbe`.

## Deliberate boundaries

- Published GuicedEE **2.5.1** is pinned. The current DevSuite checkout uses 2.6.0, which was not available from Maven Central when this experiment was created. This does not test its unpublished native-image additions.
- Original probes pin TeaVM **0.13.0** because it supports Java 25 and exposes `WEBASSEMBLY_WASI`. The separate `c-*` probes use TeaVM **0.16.0** C output, our bounded platform adapters, and wasi-sdk **34**. Neither route uses a JavaScript host.
- The Guice dependency is `com.guicedee.modules.services:guice`, not a hand-written container. All probes use the same source on JVM and WASI.
- The small explicit request scope tests Guice's `Scope` API. It is not a substitute for GuicedEE's Vert.x-backed `CallScoper`.
- This initial compiler/runtime gate emits a WASI command module, **not a WASI HTTP component**. No HTTP service, browser, or JavaScript host is involved.
- Maven 4, Java 25, GuicedEE BOM, separate JPMS main/test modules, and test package conventions follow the GuicedEE Creator skill. Its full-framework bootstrap check is deliberately inapplicable here: these substrate probes must not start Vert.x through `GuiceApplication` before the compiler/Guice gates have been assessed.
- No existing framework source, repository, submodule registration, release workflow or default Maven plugin version is changed.

## Next gate

Only after the Java and Guice gates pass should this grow a pinned WASI HTTP/component adapter and a GuicedEE request lifecycle.
That requires request-context isolation/cleanup, async completion, host capabilities, and a separate decision on Vert.x/Netty.
An HTTP process wrapper around the command module would not prove WASI HTTP compatibility.

## Upstream references

- [TeaVM 0.13 Java 25 support](https://teavm.org/docs/release-notes/0.13.0.html)
- [TeaVM 0.13 compiler targets](https://github.com/konsoletyper/teavm/blob/0.13.0/tools/core/src/main/java/org/teavm/tooling/TeaVMTargetType.java)
- [GraalVM Web Image: JavaScript runtime requirement](https://www.graalvm.org/dev/reference-manual/web-image/)
- [Wasmtime release and archive checksums](https://github.com/bytecodealliance/wasmtime/releases/tag/v50.0.1)
- [WASI components and HTTP worlds](https://component-model.bytecodealliance.org/running-components/wasmtime.html)
