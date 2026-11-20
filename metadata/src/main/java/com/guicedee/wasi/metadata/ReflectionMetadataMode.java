package com.guicedee.wasi.metadata;

import java.lang.reflect.*;
import org.teavm.model.*;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Add the missing APIs only in the separate metadata profile. */
public final class ReflectionMetadataMode implements TeaVMPlugin {
    @Override public void install(TeaVMHost host) {
        InputMetadata.initialize(host.getResourceProvider());
        host.add(new org.teavm.dependency.AbstractDependencyListener() {
            @Override public void classReached(org.teavm.dependency.DependencyAgent agent, String name) {
                // Class values flowing through Guava's erased collection/array paths can
                // lose name reachability. Keep binary names for linked classes without
                // enabling reflective member access or dynamic class lookup.
                agent.linkMethod(new MethodReference(Class.class, "getName", String.class))
                        .getVariable(0).getClassValueNode().propagate(agent.getType(ValueType.object(name)));
            }
        });
        host.add((ClassHolderTransformer) (cls, context) -> {
            restoreMemberModifiers(cls);
            if (cls.getName().equals(MetadataValues.class.getName()) || cls.getName().equals(KeySets.class.getName())) {
                // Java source cannot call JDK-internal constructors. Map the explicit
                // class-library constructors to the substituted JDK types in the emitted program.
                for (var method : cls.getMethods()) {
                    if (method.getProgram() == null) continue;
                    for (var block : method.getProgram().getBasicBlocks()) for (var instruction : block) {
                        if (instruction instanceof ConstructInstruction construct) construct.setType(shimType(construct.getType()));
                        else if (instruction instanceof InvokeInstruction invoke && invoke.getMethod().getName().equals("<init>")) {
                            invoke.setMethod(new MethodReference(shimType(invoke.getMethod().getClassName()), invoke.getMethod().getDescriptor()));
                        }
                    }
                }
            }
            if (cls.getName().contains("$proxy$") && cls.getInterfaces().stream().anyMatch(name -> {
                var iface = context.getHierarchy().getClassSource().get(name);
                return iface != null && iface.hasModifier(ElementModifier.ANNOTATION);
            })) {
                // Meta proxies omit Object contracts inherited through Annotation.
                // Restore them on generated implementations without altering annotation schemas.
                var equals = new MethodHolder(new MethodDescriptor("equals", Object.class, boolean.class));
                equals.setLevel(AccessLevel.PUBLIC); cls.addMethod(equals);
                var emitter = ProgramEmitter.create(equals, context.getHierarchy());
                emitter.invoke(MetadataValues.class.getName(), "annotationEquals", ValueType.BOOLEAN,
                        emitter.var(0, java.lang.annotation.Annotation.class), emitter.var(1, Object.class)).returnValue();
                forward(cls, context, "hashCode", "annotationHash", int.class, MetadataValues.class, java.lang.annotation.Annotation.class);
                forward(cls, context, "toString", "annotationString", String.class, MetadataValues.class, java.lang.annotation.Annotation.class);
            }
            switch (cls.getName()) {
                case "java.lang.reflect.ParameterizedTypeImpl", "java.lang.reflect.GenericArrayTypeImpl",
                        "java.lang.reflect.WildcardTypeImpl", "java.lang.reflect.TypeVariableImpl" -> {
                    if (cls.getName().equals("java.lang.reflect.ParameterizedTypeImpl")) {
                        var owner = cls.getMethod(new MethodDescriptor("getOwnerType", Type.class));
                        var original = new MethodHolder(new MethodDescriptor("$wasi$originalOwnerType", Type.class));
                        original.setLevel(AccessLevel.PRIVATE); original.setProgram(owner.getProgram()); cls.addMethod(original);
                        var ownerEmitter = ProgramEmitter.create(owner, context.getHierarchy());
                        var receiver = ownerEmitter.var(0, ValueType.object(cls.getName()));
                        ownerEmitter.invoke(RuntimeTypes.class.getName(), "ownerOrDeclaring", ValueType.parse(Type.class),
                                receiver.invokeSpecial("$wasi$originalOwnerType", Type.class),
                                receiver.invokeVirtual("getRawType", Type.class)).returnValue();
                    }
                    var equality = cls.getMethod(new MethodDescriptor("equals", Object.class, boolean.class));
                    if (equality == null) { equality = new MethodHolder(new MethodDescriptor("equals", Object.class, boolean.class)); cls.addMethod(equality); }
                    equality.setLevel(AccessLevel.PUBLIC);
                    var emitter = ProgramEmitter.create(equality, context.getHierarchy());
                    emitter.invoke(RuntimeTypes.class.getName(), "typeEquals", ValueType.BOOLEAN,
                            emitter.var(0, Type.class), emitter.var(1, Object.class)).returnValue();
                    var hash = cls.getMethod(new MethodDescriptor("hashCode", int.class));
                    if (hash == null) { hash = new MethodHolder(new MethodDescriptor("hashCode", int.class)); cls.addMethod(hash); }
                    hash.setLevel(AccessLevel.PUBLIC);
                    emitter = ProgramEmitter.create(hash, context.getHierarchy());
                    emitter.invoke(RuntimeTypes.class.getName(), "typeHash", ValueType.INTEGER, emitter.var(0, Type.class)).returnValue();
                }
                case "java.util.concurrent.ConcurrentHashMap" -> {
                    var view = ValueType.object("java.util.concurrent.ConcurrentHashMap$KeySetView");
                    for (boolean sized : new boolean[] {false, true}) {
                        var descriptor = sized ? new MethodDescriptor("newKeySet", ValueType.INTEGER, view)
                                : new MethodDescriptor("newKeySet", view);
                        if (cls.getMethod(descriptor) != null) throw new IllegalStateException("TeaVM now implements newKeySet");
                        var method = new MethodHolder(descriptor);
                        method.setLevel(AccessLevel.PUBLIC); method.getModifiers().add(ElementModifier.STATIC); cls.addMethod(method);
                        var emitter = ProgramEmitter.create(method, context.getHierarchy());
                        if (sized) emitter.invoke(KeySets.class.getName(), "create", view, emitter.var(1, int.class)).returnValue();
                        else emitter.invoke(KeySets.class.getName(), "create", view).returnValue();
                    }
                }
                case "java.lang.Class" -> {
                    forward(cls, context, "isRecord", "isRecord", boolean.class, MetadataValues.class, Class.class);
                    forward(cls, context, "isSealed", "isSealed", boolean.class, MetadataValues.class, Class.class);
                    forward(cls, context, "getPermittedSubclasses", "permittedSubclasses", Class[].class, MetadataValues.class, Class.class);
                    forward(cls, context, "getEnclosingMethod", "enclosingMethod", Method.class, MetadataValues.class, Class.class);
                    forward(cls, context, "getGenericSuperclass", "genericSuperclass", Type.class, MetadataValues.class, Class.class);
                    forward(cls, context, "getGenericInterfaces", "genericInterfaces", Type[].class, MetadataValues.class, Class.class);
                    forward(cls, context, "getAnnotatedSuperclass", "annotatedSuperclass", AnnotatedType.class, MetadataValues.class, Class.class);
                }
                case "java.lang.reflect.Field" -> forward(cls, context, "getAnnotatedType", "fieldType", AnnotatedType.class, MetadataValues.class, Field.class);
                case "java.lang.reflect.Constructor" -> {
                    var method = cls.getMethod(new MethodDescriptor("getName", String.class));
                    var emitter = ProgramEmitter.create(method, context.getHierarchy());
                    emitter.var(0, Constructor.class).invokeVirtual("getDeclaringClass", Class.class)
                            .invokeVirtual("getName", String.class).returnValue();
                }
                case "java.lang.reflect.Executable" -> {
                    forward(cls, context, "getAnnotatedParameterTypes", "parameterTypes", AnnotatedType[].class, MetadataValues.class, Executable.class);
                    forward(cls, context, "getParameters", "parameters", Parameter[].class, MetadataValues.class, Executable.class);
                }
                case "java.lang.reflect.Method" -> forward(cls, context, "getDefaultValue", "defaultValue", Object.class, MetadataValues.class, Method.class);
            }
        });
    }
    private static void restoreMemberModifiers(ClassHolder cls) {
        // TeaVM 0.16 Parser keeps the declaring class but omits InnerClasses access
        // flags. Restore static/visibility from the same registered input bytes.
        // Do not transfer host-JDK modifiers onto substituted class-library types.
        if (cls.getDeclaringClassName() == null || !(cls.getName().startsWith("com.guicedee.wasi.experiment.")
                || cls.getName().startsWith("com.google.inject."))) return;
        Class<?> input = InputMetadata.load(cls.getName());
        if (input == null || !input.isMemberClass()
                || !input.getDeclaringClass().getName().equals(cls.getDeclaringClassName())) {
            throw new IllegalStateException("Missing member-class metadata for " + cls.getName());
        }
        int flags = input.getModifiers();
        if (Modifier.isStatic(flags)) cls.getModifiers().add(ElementModifier.STATIC);
        else cls.getModifiers().remove(ElementModifier.STATIC);
        cls.setLevel(Modifier.isPublic(flags) ? AccessLevel.PUBLIC : Modifier.isProtected(flags) ? AccessLevel.PROTECTED
                : Modifier.isPrivate(flags) ? AccessLevel.PRIVATE : AccessLevel.PACKAGE_PRIVATE);
    }
    private static String shimType(String name) {
        return switch (name) {
            case "org.teavm.classlib.java.lang.reflect.TParameter" -> "java.lang.reflect.Parameter";
            case "org.teavm.classlib.java.util.concurrent.TConcurrentHashMap$KeySetView" -> "java.util.concurrent.ConcurrentHashMap$KeySetView";
            default -> name;
        };
    }
    private static void forward(ClassHolder cls, ClassHolderTransformerContext context, String name, String target, Class<?> result, Class<?> helper, Class<?> receiver) {
        var method = new MethodHolder(new MethodDescriptor(name, result));
        if (cls.getMethod(method.getDescriptor()) != null) throw new IllegalStateException("TeaVM now implements " + name);
        method.setLevel(AccessLevel.PUBLIC);
        cls.addMethod(method);
        var emitter = ProgramEmitter.create(method, context.getHierarchy());
        emitter.invoke(helper.getName(), target, ValueType.parse(result), emitter.var(0, receiver)).returnValue();
    }
}
