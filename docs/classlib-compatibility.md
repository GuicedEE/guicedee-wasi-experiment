# Cooperative class-library continuation

The optional `wasi-classlib` artifact supplies three small Java-library implementations needed by Guava:
`ConcurrentLinkedQueue`, `AtomicReferenceArray`, and a restricted `ReentrantLock` with the `Lock` interface.
They execute in the existing TeaVM 0.16 C → wasi-sdk 34 → Wasmtime route. No existing framework source was changed.

## Results on Windows (2026-11-15)

| Gate | Result |
| --- | --- |
| JVM control | PASS: 6 tests, including the same supported queue/atomic/lock assertions against the JDK |
| `c-concurrency` | PASS in Wasmtime, including positive behavior and explicit rejection of unsupported lock operations |
| `c-guice-compat` (without new class library) | FAIL: 28 distinct missing-API diagnostics |
| `c-guice-classlib` (with new class library) | FAIL: 17 distinct missing-API diagnostics |

The Guice compiler now resolves the queue, atomic array and inherited Guava lock operations. Actual Guice execution
has **not** passed. A lower diagnostic count is not a compatibility percentage; resolving one dependency can expose
additional missing APIs or runtime failures. Snapshots are in [classlib-evidence](classlib-evidence/).

## Implemented behavior

- Queue: FIFO ordering, null rejection, reuse after emptying, weakly consistent iteration and iterator removal.
- Atomic array: defensive construction, reference-identity compare-and-set, failed-CAS preservation, get-and-set,
  bounds checks, and retry when an update callback changes the value before the CAS.
- Lock: owning Java thread and reentrant hold count, nonblocking contention checks, rejection of non-owner unlock,
  release/reacquisition, `Lock` interface dispatch, and interrupt rejection/clearing before acquisition.
- Two cooperative Java threads run in Wasmtime; the contender cannot acquire or release the owner's lock.

The runtime has one host thread. Queue and reference operations do not suspend between reading and updating their
state. That makes the operations useful for this cooperative profile; it does **not** implement a shared-memory
Wasm atomic-memory model or OS-thread synchronization. The classes must not be reused in a shared-memory runtime.

Contended blocking acquisition, contended positive-timeout acquisition, fair queuing and conditions are explicitly
unsupported. The WASI probe checks that each is rejected with `UnsupportedOperationException`. A zero-timeout
contended acquisition returns false. `Condition` has an API declaration solely to preserve the `Lock` signature;
no condition implementation is supplied. Unknown/unimplemented APIs remain compiler errors.

The `--wasi-limits` branch of the probe tests these deliberate limitations. The JVM control runs only the supported
behavior checks, because real JDK locks support those additional features. Both success markers and a zero exit
status are required for the WASI gate to pass.

## Isolation

The added Maven profile is `classlib-compat`; only `c-concurrency` and `c-guice-classlib` enable it. TeaVM's standard
class-library substitution policy resolves the `org.teavm.classlib.java.*` implementations from that artifact.
The extension leaves the published GuicedEE Guice injector, Guava cache implementation, providers and scopes intact.
`c-guice-classlib` also uses the previously documented reflection-only compiler extension, so AOP remains unsupported
in this separate profile. The original full Guice/interception probe is retained as a failing acceptance gate.

## Remaining work

The current graph still reports:

- Generic superclass/interface metadata, annotated types/parameters, reflection parameter objects and annotation defaults.
- `Future`/`ListenableFuture` support, `LockSupport` parking, and Guava's reachable `Unsafe` implementation.
- `ConcurrentHashMap.newKeySet` and its key-set view API.

Completing the library surface still needs semantic probes, especially generic qualifier/nullability metadata and
future completion/cancellation/interrupt behavior. Adding empty annotation results or successful no-op parking would
invalidate the experiment. After compilation succeeds, Guice construction, providers, scopes and failure behavior
must execute before advancing to a GuicedEE/WASI HTTP lifecycle.

## Reproduce

```text
python scripts/run_probe.py jvm
python scripts/run_probe.py c-concurrency
python scripts/run_probe.py c-guice-compat
python scripts/run_probe.py c-guice-classlib
```

The [Native Image assessment](native-image-route.md) explains why the existing native executable and current Web Image
backend do not bypass these standalone-WASI requirements.
