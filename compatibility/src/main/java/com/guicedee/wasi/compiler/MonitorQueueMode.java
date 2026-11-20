package com.guicedee.wasi.compiler;

import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Bound only TeaVM's private monitor queues, leaving wait/notify/ownership intact. */
public final class MonitorQueueMode implements TeaVMPlugin {
    private static final String MONITOR = "java.lang.Object$Monitor";
    private static final String QUEUE = MonitorQueue.class.getName();
    private static final ValueType QUEUE_TYPE = ValueType.object(QUEUE);

    private static boolean queueField(FieldReference field) {
        return field.getClassName().equals(MONITOR)
                && (field.getFieldName().equals("enteringThreads") || field.getFieldName().equals("notifyListeners"));
    }
    @Override public void install(TeaVMHost host) {
        host.add((ClassHolderTransformer) (cls, context) -> {
            if (cls.getName().equals(MONITOR)) {
                for (String name : new String[] {"enteringThreads", "notifyListeners"}) {
                    var field = cls.getField(name);
                    if (field == null || !field.getType().equals(ValueType.object("java.util.Queue"))) {
                        throw new IllegalStateException("TeaVM monitor queue layout changed");
                    }
                    field.setType(QUEUE_TYPE); field.setGenericType(null);
                }
            }
            if (!cls.getName().startsWith("java.lang.Object")) return;
            for (var method : cls.getMethods()) {
                if (method.getProgram() == null) continue;
                for (var block : method.getProgram().getBasicBlocks()) for (var instruction : block) {
                    if (instruction instanceof GetFieldInstruction field && queueField(field.getField())) {
                        field.setFieldType(QUEUE_TYPE);
                    } else if (instruction instanceof PutFieldInstruction field && queueField(field.getField())) {
                        field.setFieldType(QUEUE_TYPE);
                    } else if (cls.getName().equals("java.lang.Object")) {
                        if (instruction instanceof ConstructInstruction construct && construct.getType().equals("java.util.ArrayDeque")) {
                            construct.setType(QUEUE);
                        } else if (instruction instanceof InvokeInstruction invoke) {
                            var original = invoke.getMethod();
                            if (original.getClassName().equals("java.util.ArrayDeque") && original.getName().equals("<init>")) {
                                if (original.parameterCount() != 0) throw new IllegalStateException("Unexpected monitor queue constructor");
                                invoke.setMethod(new MethodReference(QUEUE, original.getDescriptor()));
                            } else if (original.getClassName().equals("java.util.Queue")) {
                                if (!(original.getName().equals("add") || original.getName().equals("remove") || original.getName().equals("isEmpty"))) {
                                    throw new IllegalStateException("Unexpected monitor queue operation: " + original);
                                }
                                invoke.setMethod(new MethodReference(QUEUE, original.getDescriptor()));
                                invoke.setType(InvocationType.SPECIAL);
                            }
                        }
                    }
                }
            }
        });
    }
}
