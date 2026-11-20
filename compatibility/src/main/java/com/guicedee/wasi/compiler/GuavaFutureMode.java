package com.guicedee.wasi.compiler;

import org.teavm.model.*;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Select Guava 33.8's existing field-updater helper, retaining its future implementation.
 * Only the optional compatibility profile loads this compiler extension. */
public final class GuavaFutureMode implements TeaVMPlugin {
    private static final String STATE = "com.google.common.util.concurrent.AbstractFutureState";
    private static final String UNSAFE = STATE + "$UnsafeAtomicHelper";
    private static final String FALLBACK = STATE + "$AtomicReferenceFieldUpdaterAtomicHelper";

    @Override public void install(TeaVMHost host) {
        host.add((ClassHolderTransformer) (cls, context) -> {
            if (cls.getName().startsWith(STATE + "$VarHandleAtomicHelperMaker")) {
                for (var method : cls.getMethods()) {
                    if (method.getName().equals("tryMakeVarHandleAtomicHelper")) {
                        ProgramEmitter.create(method, context.getHierarchy()).constantNull(method.getResultType()).returnValue();
                    }
                }
            }
            if (!cls.getName().equals(STATE)) return;
            // This capability-restricted profile fixes Guava's optional cancellation
            // cause generation to its default (false), instead of querying JVM system
            // properties, whose TeaVM initialization reaches filesystem APIs.
            var cancellationCauses = cls.getMethod(new MethodDescriptor("computeGenerateCancellationCauses", ValueType.BOOLEAN));
            if (cancellationCauses == null) throw new IllegalStateException("Guava future configuration changed");
            ProgramEmitter.create(cancellationCauses, context.getHierarchy()).constant(0).returnValue();
            var initializer = cls.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID));
            if (initializer == null) throw new IllegalStateException("Guava future initializer changed");
            int allocations = 0;
            int constructors = 0;
            for (var block : initializer.getProgram().getBasicBlocks()) {
                for (var instruction : block) {
                    if (instruction instanceof ConstructInstruction construct && construct.getType().equals(UNSAFE)) {
                        construct.setType(FALLBACK);
                        allocations++;
                    } else if (instruction instanceof InvokeInstruction invoke && invoke.getMethod().getClassName().equals(UNSAFE)
                            && invoke.getMethod().getName().equals("<init>")) {
                        invoke.setMethod(new MethodReference(FALLBACK, invoke.getMethod().getDescriptor()));
                        constructors++;
                    }
                }
            }
            if (allocations != 1 || constructors != 1) throw new IllegalStateException("Unexpected Guava future helper layout");
            System.out.println("Guava compatibility: selected existing AtomicReferenceFieldUpdater helper; cancellation causes disabled");
        });
    }
}
