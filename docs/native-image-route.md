# Can the Native Image build produce standalone WASI?

Assessed against current upstream documentation on 2026-11-15. This is a source/documentation assessment;
no GraalVM compiler build or native-executable-to-Wasm conversion was performed in this step.

## Three different routes

| Route | What exists | Fit for this experiment |
| --- | --- | --- |
| Existing native executable → Wasm | Native Image's usual output is machine code and runtime data for a specific OS/CPU | No supported direct conversion established. Binary translation/emulation would also need to supply the original OS/runtime ABI. |
| Java bytecode → Native Image Web Image backend | `native-image --tool:svm-wasm` produces Wasm plus a JavaScript runtime wrapper | Real upstream capability, but currently depends on JavaScript imports/runtime. It does not satisfy standalone Wasmtime/WASI. |
| Native Image LLVM backend → a new WASI target | LLVM intermediate files can be retained; upstream describes how to add target architectures | A compiler/runtime port, not simply changing the LLVM target triple or passing an existing executable to wasi-sdk. |

The normal executable is created for a specific operating system and architecture, including the required
runtime and native JDK code. That is why a successful native build alone does not establish Wasm portability.
See [Native Image overview](https://www.graalvm.org/reference-manual/native-image/).

## Web Image is the closest version of the idea

The experimental Web Image backend reuses Native Image's ahead-of-time analysis while generating a different target.
It is enabled with `--tool:svm-wasm` as the first argument and is documented for Oracle GraalVM 25.1 or later.
The generated `.js.wasm` depends on its `.js` launcher/runtime; the current documentation explicitly rules out
running it directly with `wasmtime`. See [Web Image documentation](https://www.graalvm.org/dev/reference-manual/web-image/).

If JavaScript-hosted execution becomes acceptable, this deserves its own Guice compatibility probe. Existing
reachability metadata may be useful input, but success of the native build does not prove that its hosted features,
substitutions or native dependencies work with Web Image. It would be a separate target and validation result.

## Why the LLVM intermediate is not a ready-made bridge

Current upstream documentation enables this backend with `--tool:llvm-backend` and describes a source build.
Its target-port checklist includes register/trampoline and stack-frame details, LLVM GC statepoints, and object-file
relocations/data sections. The runtime's OS interfaces also need a WASI implementation. We therefore infer that
reusing its bitcode still requires a substantive target/runtime port; we have not established a supported WASI switch.
See [Native Image LLVM backend](https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.core.graal.llvm/src/com/oracle/svm/core/graal/llvm/LLVMBackend.md).

## Decision

Continue the already executing TeaVM C → wasi-sdk → Wasmtime experiment for the user's standalone WASI server target.
Keep Native Image/Web Image as a documented alternative if the required host changes or upstream gains WASI support.
The [class-library continuation](classlib-compatibility.md) tests the next concrete Guice dependencies on this route.
