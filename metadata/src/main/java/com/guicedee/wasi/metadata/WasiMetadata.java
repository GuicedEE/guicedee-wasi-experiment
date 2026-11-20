package com.guicedee.wasi.metadata;

import java.lang.reflect.*;
import java.lang.annotation.Annotation;
import java.util.*;
import org.teavm.metaprogramming.*;
import static org.teavm.metaprogramming.Metaprogramming.*;

/** Compile-time extraction; only emitted values and dispatch execute in WASI. */
@CompileTime
public final class WasiMetadata {
    @Meta public static native MetadataResult classFacts(Class<?> type);
    private static void classFacts(ReflectClass<?> type) {
        Class<?> input = hostClass(type);
        if (input == null) { unavailable(type); return; }
        int flags = (input.isRecord() ? 1 : 0) | (input.isSealed() ? 2 : 0);
        String name = type.getName();
        exit(() -> new MetadataResult(name, flags));
    }
    @Meta public static native MetadataResult permittedSubclasses(Class<?> type);
    private static void permittedSubclasses(ReflectClass<?> type) {
        Class<?> input = hostClass(type);
        if (input == null) { unavailable(type); return; }
        String name = type.getName();
        Class<?>[] permitted = input.getPermittedSubclasses();
        if (permitted == null) { exit(() -> new MetadataResult(name, null)); return; }
        // Platform classes are TeaVM substitutions, not the host JDK's sealed
        // implementations (e.g. ByteBuffer's private subclasses do not exist).
        if (!registeredMembers(type)) { unavailable(type); return; }
        int length = permitted.length;
        Value<Class<?>[]> result = emit(() -> new Class<?>[length]);
        for (int i = 0; i < length; i++) {
            int index = i;
            ReflectClass<?> item = findClass(permitted[i]);
            if (item == null) { unavailable(type); return; }
            emit(() -> result.get()[index] = item.asJavaClass());
        }
        exit(() -> new MetadataResult(name, result.get()));
    }
    @Meta public static native MetadataResult enclosingMethod(Class<?> type);
    private static void enclosingMethod(ReflectClass<?> type) {
        Class<?> input = hostClass(type);
        if (input == null) { unavailable(type); return; }
        String name = type.getName();
        Method method = input.getEnclosingMethod();
        if (method == null) { exit(() -> new MetadataResult(name, null)); return; }
        ReflectClass<?> owner = findClass(method.getDeclaringClass());
        String methodName = method.getName();
        Value<Type[]> parameters = types(method.getParameterTypes());
        exit(() -> new MetadataResult(name, MetadataValues.findMethod(owner.asJavaClass(), methodName, parameters.get())));
    }
    @Meta public static native MetadataResult genericSuperclass(Class<?> type);
    private static void genericSuperclass(ReflectClass<?> type) {
        if (!registeredHierarchy(type)) { unavailable(type); return; }
        Class<?> input = hostClass(type);
        if (input == null || !supported(input.getGenericSuperclass())) { unavailable(type); return; }
        Value<Type> value = type(input.getGenericSuperclass());
        String name = type.getName();
        exit(() -> new MetadataResult(name, value.get()));
    }
    @Meta public static native MetadataResult genericInterfaces(Class<?> type);
    private static void genericInterfaces(ReflectClass<?> type) {
        if (!registeredHierarchy(type)) { unavailable(type); return; }
        Class<?> input = hostClass(type);
        if (input == null) { unavailable(type); return; }
        // Project host signatures onto the interfaces actually implemented by
        // TeaVM's substituted class. Java 25 Integer has Constable/ConstantDesc;
        // TeaVM Integer does not. Likewise List's SequencedCollection hierarchy
        // differs. Preserve type arguments when following those intermediate edges.
        List<Type> actual = new ArrayList<>();
        for (ReflectClass<?> iface : type.getInterfaces()) {
            Type signature = interfaceSignature(input, iface.getName(), new HashMap<>());
            if (signature == null || !supported(signature)) { unavailable(type); return; }
            actual.add(signature);
        }
        Value<Type[]> value = types(actual.toArray(Type[]::new));
        String name = type.getName();
        exit(() -> new MetadataResult(name, value.get()));
    }
    private static Type interfaceSignature(Class<?> owner, String name, Map<TypeVariable<?>, Type> bindings) {
        List<Type> parents = new ArrayList<>(Arrays.asList(owner.getGenericInterfaces()));
        if (owner.getGenericSuperclass() != null) parents.add(owner.getGenericSuperclass());
        for (Type parent : parents) {
            Type resolved = substitute(parent, bindings);
            Class<?> raw = resolved instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) resolved;
            if (raw.getName().equals(name)) return resolved;
            Map<TypeVariable<?>, Type> next = new HashMap<>(bindings);
            if (resolved instanceof ParameterizedType p) {
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = p.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++) next.put(variables[i], arguments[i]);
            }
            Type found = interfaceSignature(raw, name, next);
            if (found != null) return found;
        }
        return null;
    }
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> v) return bindings.getOrDefault(v, v);
        if (type instanceof ParameterizedType p) {
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) args[i] = substitute(args[i], bindings);
            return new RuntimeTypes.Parameterized(p.getOwnerType(), (Class<?>) p.getRawType(), args);
        }
        if (type instanceof GenericArrayType a) return new RuntimeTypes.GenericArray(substitute(a.getGenericComponentType(), bindings));
        return type;
    }
    @Meta public static native MetadataResult annotatedSuperclass(Class<?> type);
    private static void annotatedSuperclass(ReflectClass<?> type) {
        if (!registeredHierarchy(type)) { unavailable(type); return; }
        Class<?> input = hostClass(type);
        if (input == null || !supported(input.getGenericSuperclass())) { unavailable(type); return; }
        Value<AnnotatedType> value = annotated(input.getAnnotatedSuperclass(), new HashMap<>());
        String name = type.getName();
        exit(() -> new MetadataResult(name, value.get()));
    }
    @Meta public static native MetadataResult annotatedField(Class<?> owner, String name);
    private static void annotatedField(ReflectClass<?> owner, Value<String> name) {
        if (!registeredMembers(owner) && !owner.getName().equals("com.google.common.util.concurrent.AbstractFutureState")
                && !owner.getName().equals("com.google.common.util.concurrent.AbstractFutureState$Waiter")) {
            unavailable(owner); return;
        }
        Class<?> input = hostClass(owner);
        if (input == null) { unavailable(owner); return; }
        Value<AnnotatedType> result = lazy(() -> MetadataValues.unavailable(name.get()));
        for (Field field : input.getDeclaredFields()) {
            if (owner.getDeclaredField(field.getName()) == null || !supported(field.getGenericType())) continue;
            String fieldName = field.getName();
            Value<AnnotatedType> value = annotated(field.getAnnotatedType(), new HashMap<>());
            Value<AnnotatedType> previous = result;
            result = lazy(() -> name.get().equals(fieldName) ? value.get() : previous.get());
        }
        Value<AnnotatedType> selected = result;
        String ownerName = owner.getName();
        exit(() -> new MetadataResult(ownerName, selected.get()));
    }
    @Meta public static native MetadataResult annotationDefault(Class<?> owner, String name);
    private static void annotationDefault(ReflectClass<?> owner, Value<String> name) {
        Class<?> input = hostClass(owner);
        if (input == null || !input.isAnnotation()) { unavailable(owner); return; }
        Value<Object> result = emit(() -> null);
        if (input.isAnnotation()) {
            for (Method method : input.getDeclaredMethods()) {
                String methodName = method.getName();
                Value<Object> value = annotationValue(method.getDefaultValue());
                Value<Object> previous = result;
                result = lazy(() -> name.get().equals(methodName) ? value.get() : previous.get());
            }
        }
        Value<Object> selected = result;
        String ownerName = owner.getName();
        exit(() -> new MetadataResult(ownerName, selected.get()));
    }
    @Meta public static native MetadataResult parameters(Class<?> owner, String signature);
    private static void parameters(ReflectClass<?> owner, Value<String> signature) {
        if (!registeredMembers(owner)) { unavailable(owner); return; }
        Class<?> input = hostClass(owner);
        if (input == null) { unavailable(owner); return; }
        Value<MetadataValues.ParameterData[]> result = lazy(() -> MetadataValues.unavailable(signature.get()));
        List<Executable> members = new ArrayList<>();
        Collections.addAll(members, input.getDeclaredMethods());
        Collections.addAll(members, input.getDeclaredConstructors());
        for (Executable executable : members) {
            String memberName = executable instanceof Constructor<?> ? "<init>" : executable.getName();
            if (owner.getDeclaredJMethod(memberName, executable.getParameterTypes()) == null
                    || !supported(executable.getGenericParameterTypes())) continue;
            String key = signature(executable);
            // All allocations and type-variable lookups belong inside the selected
            // branch. An unrelated, non-reflective generic helper must not be
            // evaluated while inspecting an injection constructor.
            Value<MetadataValues.ParameterData[]> data = lazyFragment(() -> parameterData(executable));
            Value<MetadataValues.ParameterData[]> previous = result;
            result = lazy(() -> signature.get().equals(key) ? data.get() : previous.get());
        }
        Value<MetadataValues.ParameterData[]> selected = result;
        String ownerName = owner.getName();
        exit(() -> new MetadataResult(ownerName, selected.get()));
    }
    private static Value<MetadataValues.ParameterData[]> parameterData(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        int count = parameters.length;
        Value<MetadataValues.ParameterData[]> data = emit(() -> new MetadataValues.ParameterData[count]);
        for (int i = 0; i < count; i++) {
            Parameter parameter = parameters[i];
            int index = i;
            String name = parameter.getName();
            int modifiers = parameter.getModifiers();
            boolean named = parameter.isNamePresent();
            boolean varArgs = parameter.isVarArgs();
            Value<Type> generic = type(parameter.getParameterizedType());
            Value<AnnotatedType> annotated = annotated(parameter.getAnnotatedType(), new HashMap<>());
            Value<Annotation[]> annotations = annotations(parameter.getDeclaredAnnotations());
            emit(() -> data.get()[index] = new MetadataValues.ParameterData(name, modifiers, named, varArgs,
                    generic.get(), annotated.get(), annotations.get()));
        }
        return data;
    }
    private static String signature(Executable executable) {
        StringBuilder result = new StringBuilder(executable.getName()).append('(');
        for (Class<?> parameter : executable.getParameterTypes()) result.append(parameter.getName()).append(';');
        return result.append(')').append(executable instanceof Method m ? m.getReturnType().getName() : "void").toString();
    }
    private static Value<AnnotatedType[]> annotatedTypes(AnnotatedType[] input, Map<String, Value<AnnotatedValues.Variable>> variables) {
        int count = input.length;
        Value<AnnotatedType[]> result = emit(() -> new AnnotatedType[count]);
        for (int i = 0; i < count; i++) {
            int index = i;
            Value<AnnotatedType> item = annotated(input[i], variables);
            emit(() -> result.get()[index] = item.get());
        }
        return result;
    }
    private static Value<AnnotatedType> annotated(AnnotatedType input, Map<String, Value<AnnotatedValues.Variable>> variables) {
        if (input == null) return emit(() -> null);
        Value<Type> type = type(input.getType());
        Value<Annotation[]> annotations = annotations(input.getDeclaredAnnotations());
        if (input instanceof AnnotatedTypeVariable variable) {
            TypeVariable<?> v = (TypeVariable<?>) input.getType();
            String key = v.getGenericDeclaration() + ":" + v.getName() + Arrays.toString(input.getDeclaredAnnotations());
            Value<AnnotatedValues.Variable> existing = variables.get(key);
            if (existing != null) return emit(() -> existing.get());
            Value<AnnotatedValues.Variable> result = emit(() -> new AnnotatedValues.Variable(type.get(), annotations.get()));
            variables.put(key, result);
            Value<AnnotatedType[]> bounds = annotatedTypes(variable.getAnnotatedBounds(), variables);
            emit(() -> result.get().initializeBounds(bounds.get()));
            return emit(() -> result.get());
        }
        if (input instanceof AnnotatedArrayType array) {
            Value<AnnotatedType> component = annotated(array.getAnnotatedGenericComponentType(), variables);
            return emit(() -> new AnnotatedValues.Array(type.get(), annotations.get(), component.get()));
        }
        if (input instanceof AnnotatedWildcardType wildcard) {
            Value<AnnotatedType[]> upper = annotatedTypes(wildcard.getAnnotatedUpperBounds(), variables);
            Value<AnnotatedType[]> lower = annotatedTypes(wildcard.getAnnotatedLowerBounds(), variables);
            return emit(() -> new AnnotatedValues.Wildcard(type.get(), annotations.get(), upper.get(), lower.get()));
        }
        Value<AnnotatedType> owner = annotated(input.getAnnotatedOwnerType(), variables);
        if (input instanceof AnnotatedParameterizedType parameterized) {
            Value<AnnotatedType[]> arguments = annotatedTypes(parameterized.getAnnotatedActualTypeArguments(), variables);
            return emit(() -> new AnnotatedValues.Parameterized(type.get(), annotations.get(), owner.get(), arguments.get()));
        }
        return emit(() -> new AnnotatedValues.Plain(type.get(), annotations.get(), owner.get()));
    }
    private static Value<Annotation[]> annotations(Annotation[] input) {
        int count = input.length;
        Value<Annotation[]> result = emit(() -> new Annotation[count]);
        for (int i = 0; i < count; i++) {
            int index = i;
            Value<Object> annotation = annotationValue(input[i]);
            emit(() -> result.get()[index] = (Annotation) annotation.get());
        }
        return result;
    }
    private static Value<Object> annotationValue(Object input) {
        if (input == null) return emit(() -> null);
        if (input instanceof String value) return emit(() -> value);
        if (input instanceof Boolean b) { boolean value = b; return emit(() -> Boolean.valueOf(value)); }
        if (input instanceof Byte b) { byte value = b; return emit(() -> Byte.valueOf(value)); }
        if (input instanceof Short b) { short value = b; return emit(() -> Short.valueOf(value)); }
        if (input instanceof Character b) { char value = b; return emit(() -> Character.valueOf(value)); }
        if (input instanceof Integer b) { int value = b; return emit(() -> Integer.valueOf(value)); }
        // TeaVM 0.16's meta instrumentation encodes a captured long with descriptor
        // 'L' instead of 'J'. Preserve all bits through two supported int captures.
        if (input instanceof Long b) {
            int high = (int) (b >>> 32); int low = b.intValue();
            return emit(() -> Long.valueOf(((long) high << 32) | (low & 0xffffffffL)));
        }
        if (input instanceof Float b) { float value = b; return emit(() -> Float.valueOf(value)); }
        if (input instanceof Double b) { double value = b; return emit(() -> Double.valueOf(value)); }
        if (input instanceof Class<?> cls) { ReflectClass<?> value = findClass(cls); return emit(() -> value.asJavaClass()); }
        if (input instanceof Enum<?> e) {
            var field = findClass(e.getDeclaringClass()).getDeclaredField(e.name());
            return emit(() -> field.get(null));
        }
        if (input.getClass().isArray()) {
            ReflectClass<?> component = findClass(input.getClass().getComponentType());
            int length = Array.getLength(input);
            Value<Object> result = emit(() -> Array.newInstance(component.asJavaClass(), length));
            for (int i = 0; i < length; i++) {
                int index = i;
                Value<Object> value = annotationValue(Array.get(input, i));
                emit(() -> Array.set(result.get(), index, value.get()));
            }
            return result;
        }
        if (input instanceof Annotation annotation) {
            ReflectClass<?> annotationClass = findClass(annotation.annotationType());
            Value<?> result = proxy(annotationClass, (instance, method, arguments) -> {
                switch (method.getName()) {
                    case "annotationType" -> exit(() -> annotationClass.asJavaClass());
                    case "hashCode" -> exit(() -> MetadataValues.annotationHash((Annotation) instance.get()));
                    case "equals" -> exit(() -> MetadataValues.annotationEquals((Annotation) instance.get(), arguments[0].get()));
                    case "toString" -> { String text = annotation.toString(); exit(() -> text); }
                    default -> {
                        try {
                            Value<Object> value = annotationValue(annotation.annotationType().getDeclaredMethod(method.getName()).invoke(annotation));
                            exit(() -> value.get());
                        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }
                }
            });
            return emit(() -> result.get());
        }
        throw new IllegalStateException("Unsupported annotation value " + input);
    }
    private static Class<?> hostClass(ReflectClass<?> type) {
        if (type.isPrimitive()) return switch (type.getName()) {
            case "void" -> void.class; case "boolean" -> boolean.class; case "byte" -> byte.class;
            case "short" -> short.class; case "char" -> char.class; case "int" -> int.class;
            case "long" -> long.class; case "float" -> float.class; case "double" -> double.class;
            default -> throw new IllegalStateException(type.getName());
        };
        return InputMetadata.load(type.getName());
    }
    private static void unavailable(ReflectClass<?> type) {
        // Omit unsupported specializations instead of emitting thousands of throw-only
        // functions. Runtime wrappers validate both the result and the owning class.
        unsupportedCase();
    }
    private static boolean registeredMembers(ReflectClass<?> type) {
        // The same explicit application registration as ProbeReflectionPolicy.
        // Unregistered members fail when requested; they are not reported as annotation-free.
        return type.getName().startsWith("com.guicedee.wasi.experiment.")
                || type.getName().startsWith("com.guicedee.wasi.render.");
    }
    private static boolean registeredHierarchy(ReflectClass<?> type) {
        String name = type.getName();
        return registeredMembers(type) || name.startsWith("com.google.inject.")
                || (Boolean.getBoolean("guicedee.wasi.angular.components")
                    && name.equals("com.jwebmp.core.base.angular.client.services.AnnotationHelper"))
                || name.startsWith("tools.jackson.") || name.startsWith("com.fasterxml.jackson.")
                || name.equals("java.io.Serializable")
                || name.startsWith("java.lang.") || name.startsWith("java.util.");
    }
    private static boolean supported(Type[] types) {
        for (Type type : types) if (!supported(type)) return false;
        return true;
    }
    private static boolean supported(Type type) {
        if (type == null) return true;
        if (type instanceof Class<?> cls) {
            while (cls.isArray()) cls = cls.getComponentType();
            return cls.isPrimitive() || environment().findClass(cls.getName()) != null;
        }
        if (type instanceof ParameterizedType p) return supported(p.getOwnerType()) && supported(p.getRawType()) && supported(p.getActualTypeArguments());
        if (type instanceof GenericArrayType a) return supported(a.getGenericComponentType());
        if (type instanceof WildcardType w) return supported(w.getUpperBounds()) && supported(w.getLowerBounds());
        if (type instanceof TypeVariable<?> v) return supported(v.getGenericDeclaration() instanceof Class<?> c ? c : ((Executable) v.getGenericDeclaration()).getDeclaringClass());
        return false;
    }
    private static Value<Type[]> types(Type[] input) {
        int count = input.length;
        Value<Type[]> result = emit(() -> new Type[count]);
        for (int i = 0; i < count; i++) {
            int index = i;
            Value<Type> value = type(input[i]);
            emit(() -> result.get()[index] = value.get());
        }
        return result;
    }
    private static Value<Type> type(Type input) {
        if (input == null) return emit(() -> null);
        if (input instanceof Class<?> cls) {
            ReflectClass<?> raw = findClass(cls);
            return emit(() -> raw.asJavaClass());
        }
        if (input instanceof ParameterizedType p) {
            ReflectClass<?> raw = findClass((Class<?>) p.getRawType());
            Value<Type> owner = type(p.getOwnerType());
            Value<Type[]> arguments = types(p.getActualTypeArguments());
            return emit(() -> new RuntimeTypes.Parameterized(owner.get(), raw.asJavaClass(), arguments.get()));
        }
        if (input instanceof GenericArrayType a) {
            Value<Type> component = type(a.getGenericComponentType());
            return emit(() -> new RuntimeTypes.GenericArray(component.get()));
        }
        if (input instanceof WildcardType w) {
            Value<Type[]> upper = types(w.getUpperBounds());
            Value<Type[]> lower = types(w.getLowerBounds());
            return emit(() -> new RuntimeTypes.Wildcard(upper.get(), lower.get()));
        }
        if (input instanceof TypeVariable<?> variable && variable.getGenericDeclaration() instanceof Class<?> owner) {
            ReflectClass<?> declaration = findClass(owner);
            String name = variable.getName();
            return emit(() -> RuntimeTypes.variable(declaration.asJavaClass(), name));
        }
        if (input instanceof TypeVariable<?> variable && variable.getGenericDeclaration() instanceof Executable owner) {
            ReflectClass<?> declaration = findClass(owner.getDeclaringClass());
            String key = signature(owner);
            String name = variable.getName();
            return emit(() -> RuntimeTypes.methodVariable(declaration.asJavaClass(), key, name));
        }
        throw new IllegalStateException("Unsupported generic signature " + input);
    }
}
