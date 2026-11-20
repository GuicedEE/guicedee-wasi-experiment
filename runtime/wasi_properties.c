/* Logical guest paths for Java system properties. This grants no filesystem
 * capabilities: actual file-operation symbols remain deliberately unimplemented. */
#include "file.h"
#include "string.h"
#include <stdlib.h>

static int32_t teavm_wasi_propertyPath(const char* variable, const char* fallback, char16_t** result) {
    const char* value = getenv(variable);
    if (value == NULL || value[0] != '/') value = fallback;
    int32_t length;
    *result = teavm_mbToChar16(value, &length);
    if (*result == NULL) abort();
    return length;
}

int32_t teavm_file_homeDirectory(char16_t** result) {
    return teavm_wasi_propertyPath("HOME", "/", result);
}

int32_t teavm_file_tempDirectory(char16_t** result) {
    return teavm_wasi_propertyPath("TMPDIR", "/tmp", result);
}

int32_t teavm_file_isWindows(void) { return 0; }

/* CFileSystem only calls this for Windows. A negative result requests its
 * unchanged-path fallback; no host filesystem canonicalization is attempted. */
int32_t teavm_file_canonicalize(char16_t* path, int32_t size, char16_t** result) {
    (void) path;
    (void) size;
    *result = NULL;
    return -1;
}
