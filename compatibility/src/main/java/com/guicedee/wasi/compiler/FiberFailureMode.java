package com.guicedee.wasi.compiler;

import java.util.HashMap;
import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Consume a failed suspension once; subsequent successful callbacks must not
 * replay an exception retained on the fiber by TeaVM 0.16. */
public final class FiberFailureMode implements TeaVMPlugin {
    @Override public void install(TeaVMHost host) {
        host.add((ClassHolderTransformer) (cls, context) -> {
            if (!cls.getName().equals("org.teavm.runtime.Fiber")) return;
            int repaired = 0;
            for (var method : cls.getMethods()) {
                if (!method.getName().equals("suspend") || method.getProgram() == null) continue;
                var program = method.getProgram();
                var reads = new HashMap<Variable, GetFieldInstruction>();
                for (var block : program.getBasicBlocks()) for (var instruction : block) {
                    if (instruction instanceof GetFieldInstruction read
                            && read.getField().getClassName().equals(cls.getName())
                            && read.getField().getFieldName().equals("exception")) reads.put(read.getReceiver(), read);
                }
                for (var block : program.getBasicBlocks()) for (var instruction : block) {
                    if (!(instruction instanceof RaiseInstruction raise)) continue;
                    var read = reads.get(raise.getException());
                    if (read == null) continue;
                    var nil = new NullConstantInstruction();
                    nil.setReceiver(program.createVariable()); nil.setLocation(raise.getLocation());
                    var clear = new PutFieldInstruction();
                    clear.setField(read.getField()); clear.setFieldType(read.getFieldType());
                    clear.setInstance(read.getInstance()); clear.setValue(nil.getReceiver()); clear.setLocation(raise.getLocation());
                    raise.insertPrevious(nil); raise.insertPrevious(clear);
                    repaired++;
                }
            }
            if (repaired != 1) throw new IllegalStateException("Unexpected TeaVM fiber exception layout: " + repaired);
            System.out.println("Fiber compatibility: clear consumed async exception before rethrow");
        });
    }
}
