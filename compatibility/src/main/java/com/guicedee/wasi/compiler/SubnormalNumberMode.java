package com.guicedee.wasi.compiler;

import org.teavm.model.*;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Preserve TeaVM's normal conversion; repair only binary64 subnormal rounding. */
public final class SubnormalNumberMode implements TeaVMPlugin {
    private static final String TARGET = "org.teavm.classlib.impl.text.DoubleSynthesizer";
    private static final MethodDescriptor ORIGINAL = new MethodDescriptor("$wasi$normal", long.class, int.class, boolean.class, double.class);
    @Override public void install(TeaVMHost host) {
        host.add((ClassHolderTransformer) (cls, context) -> {
            if (cls.getName().equals(TARGET)) {
                var method = cls.getMethod(new MethodDescriptor("synthesizeDouble", long.class, int.class, boolean.class, double.class));
                if (method == null || method.getProgram() == null) throw new IllegalStateException("TeaVM decimal synthesizer changed");
                var original = new MethodHolder(ORIGINAL);
                original.setLevel(AccessLevel.PUBLIC); original.getModifiers().add(ElementModifier.STATIC);
                original.setProgram(method.getProgram()); cls.addMethod(original);
                var emitter = ProgramEmitter.create(method, context.getHierarchy());
                emitter.invoke(SubnormalNumbers.class.getName(), "synthesize", ValueType.DOUBLE,
                    emitter.var(1, long.class), emitter.var(2, int.class), emitter.var(3, boolean.class)).returnValue();
            }
            if (cls.getName().equals(SubnormalNumbers.class.getName())) {
                for (var method : cls.getMethods()) {
                    if (method.getProgram() == null) continue;
                    for (var block : method.getProgram().getBasicBlocks()) for (var instruction : block) {
                        if (instruction instanceof InvokeInstruction invoke && invoke.getMethod().getClassName().equals(cls.getName())
                            && invoke.getMethod().getName().equals("original")) invoke.setMethod(new MethodReference(TARGET, ORIGINAL));
                    }
                }
            }
        });
    }
}
