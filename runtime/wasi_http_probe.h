#ifndef GUICEDEE_HTTP_PROBE_H
#define GUICEDEE_HTTP_PROBE_H
#include <stdint.h>
void guicedee_http_flush(void);
int32_t guicedee_http_exchange(const uint8_t *, int32_t, const uint8_t *, int32_t,
    const uint8_t *, int32_t, int32_t, const uint8_t *, int32_t, int32_t,
    uint8_t *, int32_t, int32_t *);
int32_t guicedee_http_json_exchange(const uint8_t *, int32_t, const uint8_t *, int32_t,
    const uint8_t *, int32_t, int32_t, const uint8_t *, int32_t, int32_t,
    uint8_t *, int32_t, int32_t *);
#endif
