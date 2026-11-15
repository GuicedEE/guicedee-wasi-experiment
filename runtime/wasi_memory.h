/* WASI linear memory has no mmap reservation/protection. Reserve and zero the
 * full bounded heap up front. Logical uncommit clears bytes but retains capacity. */
#include <stdio.h>
static int64_t teavm_pageSize(void) { return 65536; }
static void* teavm_virtualAlloc(int64_t size) {
    if (size <= 0 || (uint64_t) size > SIZE_MAX) abort();
    void* result = calloc(1, (size_t) size);
    if (result == NULL) {
        fputs("WASI TeaVM heap allocation failed\n", stderr);
        abort();
    }
    return result;
}
static void teavm_virtualCommit(void* address, int64_t size) {
    /* All reserved memory is already backed by the successful calloc above. */
    (void) address;
    (void) size;
}
static void teavm_virtualUncommit(void* address, int64_t size) {
    memset(address, 0, (size_t) size);
}
