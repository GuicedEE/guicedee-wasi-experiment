"""Build an explicitly restricted Jackson 3.3 profile; never overwrite upstream jars.

The parser, token handling, bean discovery, serializers/deserializers and type system
remain Jackson. Adapt member invocation/cache primitives and reject optional types.
Every changed source is emitted alongside a unified diff for review.
"""
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile
from wasmtime_tool import ROOT

VERSION = '3.3.0-guicedee-wasi-1'
SOURCE_HASHES = {
    'jackson-core': 'a297eb4641231dbc0f3d8f196f6d70416618e3991b5d9a9309a7ba37704c8ad1',
    'jackson-databind': 'f3fcbbf4e2a52840136294bc1edd046eefecdacf13007f1ced93396db5106a67',
}
JAR_HASHES = {
    'tools/jackson/core/jackson-core/3.3.0/jackson-core-3.3.0.jar': '1496eda4c353276416bd4f672fb3f98be95fb4c86707e8ffcebe04425d63aa63',
    'tools/jackson/core/jackson-databind/3.3.0/jackson-databind-3.3.0.jar': '856b7348bd08efe5791cf617cc3bed21f5f4a02569507c61e946666d13c33ba4',
    'com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar': '21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0',
}

def method(source, signature, body):
    start = source.index(signature)
    begin = start + len(signature) - 1 if signature.endswith('{') else source.index('{', start)
    # Braces in strings/comments are not syntax.
    mask = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda m: ' ' * len(m.group()), source)
    depth, end = 1, begin + 1
    while depth:
        depth += (mask[end] == '{') - (mask[end] == '}')
        end += 1
    return source[:begin+1] + '\n' + body + '\n    ' + source[end-1:]

def build():
    cache = ROOT / '.cache/jackson-sources'
    target = ROOT / 'target/jackson-profile'
    target.mkdir(parents=True, exist_ok=True); cache.mkdir(parents=True, exist_ok=True)
    jars, changes = {}, {}
    for relative, checksum in JAR_HASHES.items():
        binary = ROOT / '.cache/m2' / relative
        if not binary.exists():
            binary.parent.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/' + relative, binary)
        if hashlib.sha256(binary.read_bytes()).hexdigest() != checksum:
            raise RuntimeError('Jackson binary checksum mismatch: ' + relative)
    for artifact, sha in SOURCE_HASHES.items():
        sourcejar = cache / f'{artifact}-3.3.0-sources.jar'
        if not sourcejar.exists():
            urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/tools/jackson/core/{artifact}/3.3.0/{sourcejar.name}', sourcejar)
        if hashlib.sha256(sourcejar.read_bytes()).hexdigest() != sha:
            raise RuntimeError('Jackson source checksum mismatch: ' + artifact)
        jars[artifact] = ROOT / f'.cache/m2/tools/jackson/core/{artifact}/3.3.0/{artifact}-3.3.0.jar'
    sources = {a: zipfile.ZipFile(cache / f'{a}-3.3.0-sources.jar') for a in jars}

    def edit(artifact, name, transform):
        path = 'tools/jackson/' + ('core/' if artifact == 'jackson-core' else 'databind/') + name + '.java'
        original = sources[artifact].read(path).decode()
        updated = transform(original)
        if updated == original: raise RuntimeError('No source change: ' + path)
        file = target / 'sources' / path
        file.parent.mkdir(parents=True, exist_ok=True); file.write_text(updated, encoding='utf-8')
        changes[path] = {'source_sha256': hashlib.sha256(original.encode()).hexdigest(),
                         'adapted_sha256': hashlib.sha256(updated.encode()).hexdigest()}
        diff = target / 'diffs' / (name.replace('/', '_') + '.patch')
        diff.parent.mkdir(exist_ok=True)
        diff.write_text(''.join(difflib.unified_diff(original.splitlines(True), updated.splitlines(True),
            fromfile='upstream/' + path, tofile='wasi/' + path)), encoding='utf-8')

    def replace(source, old, new):
        if old not in source: raise RuntimeError('Pinned source drift: ' + old[:100])
        return source.replace(old, new)

    # JDK reflection already qualified in the separate Guice metadata profile.
    # Unwrap InvocationTargetException to match MethodHandle target exceptions.
    for name, member in [('AnnotatedConstructor', '_constructor'), ('AnnotatedMethod', '_method')]:
        def adapt(s, name=name, member=member):
            s = re.sub(r'private static final MethodType (INVOKER_\w+_TYPE) = methodType\([^;]+;', r'private static final MethodType \1 = null;', s)
            base = '_constructor.newInstance' if name == 'AnnotatedConstructor' else '_method.invoke'
            prefix = '' if name == 'AnnotatedConstructor' else 'null, '
            for signature, args in [('Object call()', 'new Object[0]'), ('Object call(Object[] args)', 'args'), ('Object call1(Object arg)', 'new Object[]{arg}')]:
                s = method(s, signature, f'        try {{ return {base}({prefix}{args}); }}\n        catch (java.lang.reflect.InvocationTargetException e) {{ throw ClassUtil.sneakyThrow(e.getCause()); }}')
            if name == 'AnnotatedMethod':
                for signature, args in [('Object callOn(Object pojo)', 'new Object[0]'), ('Object callOnWith(Object pojo, Object... args)', 'args')]:
                    s = method(s, signature, f'        try {{ return _method.invoke(pojo, {args}); }}\n        catch (java.lang.reflect.InvocationTargetException e) {{ throw ClassUtil.sneakyThrow(e.getCause()); }}')
            return s
        edit('jackson-databind', 'introspect/' + name, adapt)
    def writer(s):
        s = replace(s, 'methodType(Object.class, Object.class)', 'null')
        s = replace(s, 'return _accessorHandle().invokeExact(bean);', 'return _member.getValue(bean);')
        return s
    edit('jackson-databind', 'ser/BeanPropertyWriter', writer)
    def setter(s):
        s = re.sub(r'methodType\((?:void|Object).class, Object.class, Object.class\)', 'null', s)
        s = replace(s, '_setterHandle().invokeExact(instance, value);', '_annotated.setValue(instance, value);')
        s = replace(s, 'Object result = _setterReturnHandle().invokeExact(instance, value);',
                    'Object result;\n            if (_annotated instanceof AnnotatedMethod m) result = m.callOnWith(instance, value);\n            else { _annotated.setValue(instance, value); result = null; }')
        return s
    edit('jackson-databind', 'deser/impl/MethodProperty', setter)
    # TeaVM aliases Collections.emptyList() to List.of(); its removeIf rejects
    # even an empty list, whereas JDK Collections.emptyList() returns false.
    edit('jackson-databind', 'introspect/AnnotatedCreatorCollector', lambda s:
        replace(replace(s, 'constructors.removeIf(', 'if (!constructors.isEmpty()) constructors.removeIf('),
                'factories.removeIf(', 'if (!factories.isEmpty()) factories.removeIf('))

    # A bounded LRU appropriate to cooperative execution, not a replacement codec.
    def cache_adapt(s):
        s = replace(s, 'protected final transient PrivateMaxEntriesMap<K,V> _map;', 'protected final transient java.util.Map<K,V> _map;')
        return method(s, 'public SimpleLookupCache(int initialEntries, int maxEntries)', '''        if (maxEntries < 0) throw new IllegalArgumentException("Negative cache bound");
        _initialEntries = initialEntries; _maxEntries = maxEntries;
        // Cooperative guest only: these bounded map operations never suspend.
        _map = new java.util.LinkedHashMap<K,V>(initialEntries, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) { return size() > _maxEntries; }
        };''')
    edit('jackson-databind', 'util/SimpleLookupCache', cache_adapt)
    edit('jackson-core', 'util/JsonRecyclerPools', lambda s: method(s, 'RecyclerPool<BufferRecycler> defaultPool()', '        return nonRecyclingPool();'))
    def buffer(s):
        return replace(s, 'private final static VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Object[].class);', '''private final static Slots SLOTS = new Slots();
    private static final class Slots {
        // Non-suspending operations on a single cooperative guest thread.
        Object getAndSet(Object[] a, int i, Object v) { Object old=a[i]; a[i]=v; return old; }
        Object getVolatile(Object[] a, int i) { return a[i]; }
        boolean compareAndSet(Object[] a, int i, Object expected, Object v) { if(a[i]!=expected) return false; a[i]=v; return true; }
    }''')
    edit('jackson-core', 'util/BufferRecycler', buffer)
    # Portable numeric fallback already offered by NumberOutput; fast writer is excluded.
    def number(s):
        s = replace(s, 'useFastWriter ? XJBWriter.toString(v) : Double.toString(v)', 'Double.toString(v)')
        s = replace(s, 'useFastWriter ? XJBWriter.toString(v) : Float.toString(v)', 'Float.toString(v)')
        for kind in ['Float', 'Double']:
            s = replace(s, f'return XJBWriter.write{kind}(v, b, off);', f'String text = {kind}.toString(v);\n        for (int i = 0; i < text.length(); i++) b[off + i] = (byte) text.charAt(i);\n        return off + text.length();')
        # char[] overloads need char, and use the same ASCII representation.
        s = re.sub(r'(public static int output(?:Float|Double)\([^{{]*char\[\][\s\S]*?)(?=\n    })', lambda m: m[0].replace('(byte) text.charAt(i)', 'text.charAt(i)'), s)
        return s
    edit('jackson-core', 'io/NumberOutput', number)
    edit('jackson-core', 'json/UTF8JsonGenerator', lambda s: replace(s, 'text.getBytes(start, offset, _outputBuffer, _outputTail);',
        'for (int i = start; i < offset; i++) _outputBuffer[_outputTail + i - start] = (byte) text.charAt(i);'))

    # Desktop/XML/SQL, Java time and miscellaneous platform DTOs are outside this
    # profile. Requests for them must fail explicitly, never silently map a bean.
    edit('jackson-databind', 'ext/beans/JavaBeansAnnotations', lambda s: method(s, 'static {', '        IMPL = null;'))
    edit('jackson-databind', 'cfg/MapperBuilder', lambda s: replace(s, 'JavaTimeInitializer.getInstance().setupModule(ctxt);', '/* WASI DTO profile excludes automatic java.time handlers. */'))
    def optional(s):
        s = replace(s, 'org.w3c.dom.Node.class', 'null')
        for sig in ['ValueSerializer<?> findSerializer(', 'ValueDeserializer<?> findDeserializer(']:
            s = method(s, sig, '        guard(type.getRawClass()); return null;')
        s = method(s, 'boolean hasDeserializerFor(', '        guard(valueType); return false;')
        return s[:s.rfind('}')] + '''    private static void guard(Class<?> type) {
        String n = type.getName();
        if (n.startsWith("javax.xml.") || n.startsWith("org.w3c.") || n.startsWith("java.sql.") || n.startsWith("java.time."))
            throw new UnsupportedOperationException("Outside WASI Jackson DTO profile: " + n);
    }
}'''
    edit('jackson-databind', 'ext/OptionalHandlerFactory', optional)
    # Remove only fallback registrations whose Java classes are absent in TeaVM.
    edit('jackson-databind', 'deser/BasicDeserializerFactory', lambda s: re.sub(r'fallbacks.put\(java.util.concurrent.ConcurrentNavigableMap.class.getName\(\),\s*java.util.concurrent.ConcurrentSkipListMap.class\);', '', s))
    def primary(s):
        for cls in ['InetAddress', 'InetSocketAddress']:
            old = f'if ({cls}.class.isAssignableFrom(raw)) {{\n            return new {cls}Serializer();\n        }}'
            s = replace(s, old, '')
        return s
    edit('jackson-databind', 'ser/BasicSerializerFactory', primary)
    def misc(s):
        s = method(s, 'static {', '        _classNames.add(Void.class.getName());')
        s = method(s, 'ValueDeserializer<?> find(', '''        if (rawType == Void.class) return NullifyingDeserializer.instance;
        if (clsName.startsWith("java.")) throw new UnsupportedOperationException("Outside WASI Jackson DTO profile: " + clsName);
        return null;''')
        return s
    edit('jackson-databind', 'deser/jdk/JDKMiscDeserializers', misc)
    edit('jackson-databind', 'ser/jdk/JDKMiscSerializers', lambda s: replace(s, 'return new UUIDSerializer();', 'throw new UnsupportedOperationException("UUID outside WASI DTO profile");'))
    # Key factories otherwise reach FromString and UUID platform codecs.
    edit('jackson-databind', 'deser/jdk/JDKFromStringDeserializer', lambda s: method(s, 'public static JDKFromStringDeserializer findDeserializer(', '        throw new UnsupportedOperationException("JDK string-converted types outside WASI DTO profile");'))
    edit('jackson-databind', 'ser/jdk/JDKKeySerializers', lambda s: method(s, 'private static String _localeToKey(', '        throw new UnsupportedOperationException("Locale map keys outside WASI DTO profile");'))
    # Record/modern class metadata is supplied by the WASI plugin; record mapping
    # is an explicit later gate, and must not fall through as an ordinary bean.
    edit('jackson-databind', 'util/RecordUtil', lambda s: method(method(s, 'String[] getRecordFieldNames(', '        throw new UnsupportedOperationException("Record DTOs not yet qualified");'), 'PotentialCreator findCanonicalRecordConstructor(', '        throw new UnsupportedOperationException("Record DTOs not yet qualified");'))
    edit('jackson-databind', 'deser/bean/BeanDeserializer', lambda s: method(s, 'private Method[] _resolveRecordAccessors(', '        throw new UnsupportedOperationException("Record DTO updates not yet qualified");'))
    # The Guice-composed program activates a coroutine transformation failure in
    # builder/external-type-id deserialization. Builders are outside this POJO
    # profile and must fail explicitly instead of reaching that compiler path.
    edit('jackson-databind', 'deser/BeanDeserializerFactory', lambda s: method(s,
        'public ValueDeserializer<Object> createBuilderBasedDeserializer(',
        '        throw new UnsupportedOperationException("Builder DTOs outside WASI Jackson profile");'))
    edit('jackson-databind', 'util/ClassUtil', lambda s: method(s, 'Method[] getClassMethods(', '        return cls.getDeclaredMethods();'))

    annotations = ROOT / '.cache/m2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar'
    # Fresh compiler output prevents a removed adapter class surviving a rebuild.
    classes = Path(tempfile.mkdtemp(prefix='classes-', dir=target))
    files = [target / 'sources' / name for name in sorted(changes)]
    subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str,[*jars.values(), annotations])), '-d', str(classes), *map(str, files)], check=True)
    for artifact, original in jars.items():
        prefix = 'tools/jackson/' + ('core/' if artifact == 'jackson-core' else 'databind/')
        output = target / f'{artifact}-{VERSION}.jar'
        replacements = {p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class') if p.relative_to(classes).as_posix().startswith(prefix)}
        with zipfile.ZipFile(original) as src, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as dst:
            for entry in src.infolist():
                if entry.filename not in replacements: dst.writestr(entry, src.read(entry))
            for path, data in sorted(replacements.items()):
                info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
                dst.writestr(info, data)
        deps = f'<dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-core</artifactId><version>{VERSION}</version></dependency><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId><version>2.22</version></dependency>' if artifact == 'jackson-databind' else ''
        (target / (artifact + '.pom')).write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>tools.jackson.core</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version><dependencies>{deps}</dependencies></project>')
    adapted = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in target.glob(f'*-{VERSION}.jar')}
    (target / 'profile.json').write_text(json.dumps({'version': VERSION, 'source_archives': SOURCE_HASHES,
        'original_binaries': JAR_HASHES, 'adapted_jars': adapted, 'changes': changes}, indent=2)+'\n')
    print('Jackson profile built:', VERSION, len(changes), 'adapted sources')

if __name__ == '__main__': build()
