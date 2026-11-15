"""Adapt only generated platform runtime files. Unsupported filesystem use fails linking."""
from pathlib import Path
import shutil
from wasmtime_tool import ROOT

def prepare(directory):
    directory = Path(directory)
    source = directory / 'all.c'
    code = source.read_text()
    for before, after in [('#include "fiber.c"', '#include "wasi_fiber.c"'),
                          ('#include "file.c"', '/* Filesystem runtime excluded; references must fail linking. */')]:
        if code.count(before) != 1:
            raise RuntimeError(f'Unexpected TeaVM generated source: {before}')
        code = code.replace(before, after)
    (directory / 'wasi_all.c').write_text(code)
    shutil.copyfile(ROOT / 'runtime/wasi_fiber.c', directory / 'wasi_fiber.c')
    memory = (directory / 'memory.c').read_text()
    if memory.count('#if defined(__EMSCRIPTEN__)') != 1:
        raise RuntimeError('Unexpected TeaVM memory implementation')
    memory = memory.replace('#if TEAVM_UNIX', '#if TEAVM_UNIX && !defined(__wasi__)', 1)
    memory = memory.replace('#if defined(__EMSCRIPTEN__)',
                            '#if defined(__wasi__)\n#include "wasi_memory.h"\n#elif defined(__EMSCRIPTEN__)')
    (directory / 'wasi_memory.c').write_text(memory)
    shutil.copyfile(ROOT / 'runtime/wasi_memory.h', directory / 'wasi_memory.h')
    if code.count('#include "memory.c"') != 1:
        raise RuntimeError('Unexpected TeaVM memory include')
    (directory / 'wasi_all.c').write_text(code.replace('#include "memory.c"', '#include "wasi_memory.c"'))
    # TeaVM packs class addresses by shifting three bits. On wasm32 the vtable
    # type can have a size not divisible by 8; align every pool slot, not just the array.
    array = (directory / 'arrayclass.c').read_text()
    replacements = {
        'static TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];':
            'typedef struct { alignas(8) TEAVM_OBJECT_CLASS value; } TeaVM_WasiClassSlot;\n'
            'static TeaVM_WasiClassSlot teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];',
        '&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++]':
            '&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++].value',
        '&teavm_dynamicClassPool[index].parent': '&teavm_dynamicClassPool[index].value.parent',
    }
    for before, after in replacements.items():
        if array.count(before) != 1:
            raise RuntimeError(f'Unexpected array-class pool: {before}')
        array = array.replace(before, after)
    (directory / 'wasi_arrayclass.c').write_text(array)
    source = directory / 'wasi_all.c'
    source.write_text(source.read_text().replace('#include "arrayclass.c"', '#include "wasi_arrayclass.c"'))

if __name__ == '__main__':
    import sys
    prepare(sys.argv[1])
