package com.guicedee.wasi.compiler;

import org.teavm.model.*;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Select Guice's existing reflection-only mode at compilation, so unreachable
 * dynamic bytecode generation can be eliminated. Interception remains unsupported
 * in this explicitly separate profile; no injection or scope implementation is replaced. */
public final class GuiceReflectionMode implements TeaVMPlugin {
    @Override public void install(TeaVMHost host) {
        ProbeReflectionPolicy.initialize(host.getResourceProvider());
        host.add((ClassHolderTransformer) (cls, context) -> {
            if (cls.getName().equals("org.teavm.runtime.reflect.MethodInfo")) {
                var call = cls.getMethod(new MethodDescriptor("call", Object.class, Object[].class, Object.class));
                // The intrinsic invokes arbitrary Java, so it needs a managed call site
                // for exception routing and roots across target allocation/suspension.
                call.getAnnotations().remove("org.teavm.interop.Unmanaged");
            }
            if (cls.getName().equals("java.lang.reflect.Method") || cls.getName().equals("java.lang.reflect.Constructor")) {
                for (var member : cls.getMethods()) {
                    if (member.getProgram() == null) continue;
                    for (var block : member.getProgram().getBasicBlocks()) for (var instruction : block) {
                        if (instruction instanceof InvokeInstruction call && call.getMethod().getClassName().equals("org.teavm.runtime.reflect.MethodInfo")
                                && call.getMethod().getName().equals("call")) {
                            var arguments = call.getArguments();
                            call.setArguments(call.getInstance(), arguments.get(0), arguments.get(1));
                            call.setInstance(null);
                            call.setType(InvocationType.SPECIAL);
                            call.setMethod(new MethodReference(ReflectionInvocation.class, "call",
                                    org.teavm.runtime.reflect.MethodInfo.class, Object.class, Object[].class, Object.class));
                        }
                    }
                }
            }
            if (cls.getName().equals("com.google.inject.internal.BytecodeGen")) {
                for (var method : cls.getMethods()) {
                    if (method.getName().equals("fastConstructor") || method.getName().equals("fastMethod")) {
                        // Guice explicitly treats null as a request for its reflection fallback.
                        ProgramEmitter.create(method, context.getHierarchy()).constantNull(method.getResultType()).returnValue();
                    } else if (method.getName().equals("enhancerBuilder")) {
                        var emitter = ProgramEmitter.create(method, context.getHierarchy());
                        emitter.construct(UnsupportedOperationException.class,
                                emitter.constant("Guice WASI reflection profile does not support interception")).raise();
                    }
                }
                System.out.println("Guice compatibility: reflective invokers selected; enhancer requests fail explicitly");
                return;
            }
            if (!cls.getName().equals("com.google.inject.internal.InternalFlags")) return;
            var method = cls.getMethod(new MethodDescriptor("isBytecodeGenEnabled", ValueType.BOOLEAN));
            if (method == null) throw new IllegalStateException("Guice bytecode flag API changed");
            ProgramEmitter.create(method, context.getHierarchy()).constant(0).returnValue();
            System.out.println("Guice compatibility: selected reflection-only construction; interception disabled");
        });
    }
}
