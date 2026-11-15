package com.guicedee.wasi.compiler;

import org.teavm.model.*;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Select Guice's existing reflection-only mode at compilation, so unreachable
 * dynamic bytecode generation can be eliminated. Interception remains unsupported
 * in this explicitly separate profile; no injection or scope implementation is replaced. */
public final class GuiceReflectionMode implements TeaVMPlugin {
    @Override public void install(TeaVMHost host) {
        host.add((ClassHolderTransformer) (cls, context) -> {
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
