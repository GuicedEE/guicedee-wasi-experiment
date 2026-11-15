/* Host adapter for TeaVM's single-threaded cooperative event queue.
 * WASI clock polling replaces POSIX signal timers. External threads and
 * indefinite external wakeups are deliberately unsupported and fail loudly. */
#include "fiber.h"
#include <wasi/api.h>
#include <stdio.h>
#include <stdlib.h>

static int teavm_pendingWakeup;

void teavm_initFiber(void) {
    teavm_pendingWakeup = 0;
}

void teavm_interrupt(void) {
    teavm_pendingWakeup = 1;
}

void teavm_waitFor(int64_t timeout) {
    if (teavm_pendingWakeup) {
        teavm_pendingWakeup = 0;
        return;
    }
    if (timeout == 0) return;
    if (timeout < 0 || (uint64_t) timeout > UINT64_MAX / 1000000) {
        fputs("Unsupported WASI event-queue wait\n", stderr);
        abort();
    }
    __wasi_subscription_t subscription = {0};
    subscription.u.tag = __WASI_EVENTTYPE_CLOCK;
    subscription.u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
    subscription.u.u.clock.timeout = (uint64_t) timeout * 1000000;
    __wasi_event_t event = {0};
    size_t count = 0;
    __wasi_errno_t error = __wasi_poll_oneoff(&subscription, &event, 1, &count);
    if (error || count != 1 || event.error) {
        fputs("WASI clock polling failed\n", stderr);
        abort();
    }
}
