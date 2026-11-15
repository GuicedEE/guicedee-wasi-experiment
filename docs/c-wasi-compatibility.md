# C-to-WASI compatibility experiment

The TeaVM 0.16 C backend plus wasi-sdk 34 provides a working standalone-WASI route for selected Java reflection
and runtime operations. **Actual Guice still does not compile**, including the new reflection-only profile.
The published GuicedEE dependency remains 2.5.1; no DevSuite framework source was modified.

## Tested results (Windows and Linux, 2026-11-15)

| Probe | Result | Evidence |
| --- | --- | --- |
| JVM control | PASS, 5 tests | Both original Guice tests plus baseline, reflection, runtime |
| Original TeaVM 0.13 WASI baseline | PASS, original checksum unchanged | Regression control |
| `c-baseline` | PASS | Java allocation, stream computation, exception handling and stdout |
| `c-runtime` | PASS | 16 MiB live heap across explicit GC, array clone independence, 25 ms sleep |
| `c-reflection` | PASS | Reflective constructor/method invocation, `@Inject` constructor annotation, `List<String>` field metadata |
| `c-guice-minimal` | FAIL, 35 distinct missing-API diagnostics | Unmodified Guice through the newer compiler |
| `c-guice-compat` | FAIL, 28 distinct missing-API diagnostics | Explicit Guice reflection mode and fallback substitutions |

These diagnostic counts are observations from one graph, not a completeness percentage or an estimate of remaining effort.
No Guice Wasm execution, HTTP component or full GuicedEE bootstrap passed in this experiment.
Machine-readable snapshots and missing-API lists are in [c-evidence](c-evidence/).

[GitHub Actions run 43737015632](https://github.com/GuicedEE/guicedee-wasi-experiment/actions/runs/43737015632)
confirmed every result above on Ubuntu 24.04 at source commit `8bafcb8d389b2be5d406245c5bcafd664d796185`.
All five JVM tests passed with zero failures or skips. All four Java/WASI baseline, runtime and reflection jobs
passed execution in Wasmtime. The four Guice jobs failed at compilation, with 38 diagnostics on each original
WASI probe, 35 on raw C, and 28 with the reflection-only compatibility extension. The overall workflow remains red.
The Linux [result snapshot](c-evidence/linux-results.json) records artifact hashes separately; the C-route binaries
are not byte-identical across the Windows and Linux builds. Logs, generated C and Wasm binaries are attached to the run.

## Why another compiler route

[TeaVM 0.14](https://teavm.org/docs/release-notes/0.14.0.html) introduced generic reflection while removing the old WASI backend.
[TeaVM 0.16](https://teavm.org/docs/release-notes/0.16.0.html) brought reflective construction and invocation to its C backend.
The experiment therefore uses Java 25 bytecode → TeaVM C → wasi-sdk clang → Wasmtime, with no JavaScript host.
This is a local experimental port, not an upstream-supported TeaVM WASI target.

## Platform adapters implemented

1. **Heap allocation:** WASI linear memory cannot use TeaVM's POSIX mmap reservation model. The adapter allocates and zeroes
   the bounded maximum heap up front. Commit needs no additional allocation; logical uncommit clears bytes but retains capacity.
   This trades memory footprint for a small, explicit implementation. It does not emulate page protection or return capacity to the OS.
2. **Cooperative event queue:** WASI `poll_oneoff` clock subscriptions implement finite waits. A pending-wakeup flag handles
   notifications within the single-threaded queue. Indefinite external waits fail explicitly. This does not implement OS threads.
3. **Class-pool alignment:** TeaVM packs class addresses by shifting three bits. The generated dynamic-array-class pool did not
   guarantee eight-byte alignment for every slot on wasm32. The guarded patch aligns each slot, fixing the observed array-clone
   dispatch trap. The runtime probe exercises cloning and GC after that change.
4. **Exceptions:** clang's setjmp/longjmp support is enabled with Wasm exception handling and linked with wasi-sdk's `libsetjmp`.
   LLVM is configured to emit the current exception format, which Wasmtime accepts.

`scripts/prepare_c.py` writes separate adapted translation units; the original generated files remain available for inspection.
The POSIX filesystem implementation is excluded. Any reachable unresolved filesystem call must fail linking; no fake filesystem
success implementation is provided. Allocation failure, polling failure and unsupported waits abort explicitly.

## Guice compatibility changes

The optional compiler-extension JAR:

- Selects Guice's existing reflection-only construction mode at compile time.
- Makes Guice fast-constructor/fast-method requests return its documented null fallback, retaining the original reflective invokers.
- Makes enhancer requests throw an explicit unsupported-interception exception. This separate profile does not claim AOP support.
- Retains a closed set of application members through TeaVM's reflection policy so actual constructor/method invocation is testable.

It does not substitute a different injector, preconstruct application services, or change Guice scopes/providers.
Both raw and adapted probes remain failing jobs, with the original interception probe retained as a separate acceptance gate.

## Remaining blockers

The adapted graph still needs APIs that TeaVM does not supply, including:

- Annotated parameter/field types, reflection parameter objects, generic superclass/interface access and annotation defaults.
- Guava cache locking and queues: `ReentrantLock`, `ConcurrentLinkedQueue`, `AtomicReferenceArray`, and additional concurrent-map APIs.
- Future/parking support and Guava's use of `sun.misc.Unsafe`.

The successful reflection probe proves a useful subset, not full JDK reflection compatibility. Disabling bytecode generation removes
some dynamic-code dependencies but does not solve the remaining graph. No locks, atomics or reflection APIs were replaced with no-op stubs.

The next Guice step requires a substantive class-library/Guava port or a deliberately supported alternative implementation of those
facilities. The compiler/runtime path can now execute meaningful probes, but it is not yet a runnable GuicedEE service target.

## Reproduce

```text
python scripts/run_probe.py c-baseline
python scripts/run_probe.py c-runtime
python scripts/run_probe.py c-reflection
python scripts/run_probe.py c-guice-minimal
python scripts/run_probe.py c-guice-compat
```

Compiler, runtime, adapter output and errors are preserved under `target/evidence` and `target/c`.
The CI matrix includes the original four gates and all five C-route gates; failed probes keep the overall workflow red.
