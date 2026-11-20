/* Bounded synchronous guest adapter over standard WASI HTTP 0.2 imports.
 * No sockets, native HTTP library, subprocess, or mock responses live here.
 * Every acquired resource is released or transferred, children before parents.
 * Return: 0 HTTP response (including non-2xx), 1 HTTP/stream failure,
 * 2 invalid input/host operation, 3 response body exceeds caller's bound,
 * 4 guest-enforced first-byte or between-bytes deadline.
 * Metadata: status, bytes, WASI error tag (-1 if absent), live, peak, echoed token.
 */
#include "wasi_http_probe.h"
#include "outbound_probe.h"
#include <string.h>
#include <stdio.h>

void guicedee_http_flush(void) { fflush(stdout); fflush(stderr); }

#define ACQUIRE(name) do { have_##name = true; if (++live > peak) peak = live; } while (0)
#define TRANSFER(name) do { have_##name = false; --live; } while (0)
#define DROP(name, fn) do { if (have_##name) { fn(name); TRANSFER(name); } } while (0)
#define RESOURCE(type, name) type name = {0}; bool have_##name = false

static int http_failure(wasi_http_types_error_code_t *error, int32_t *meta) {
    meta[2] = error->tag;
    wasi_http_types_error_code_free(error);
    return 1;
}

static int stream_failure(wasi_io_streams_stream_error_t *error, int32_t *meta) {
    if (error->tag == WASI_IO_STREAMS_STREAM_ERROR_LAST_OPERATION_FAILED) {
        wasi_http_types_error_code_t detail;
        if (wasi_http_types_http_error_code(wasi_io_error_borrow_error(error->val.last_operation_failed), &detail))
            http_failure(&detail, meta);
        else {
            outbound_probe_string_t description;
            wasi_io_error_method_error_to_debug_string(wasi_io_error_borrow_error(error->val.last_operation_failed), &description);
            fprintf(stderr, "HTTP_STREAM_ERROR %.*s\n", (int)description.len, description.ptr);
            outbound_probe_string_free(&description);
        }
    }
    /* Also drops the owned io:error handle, when present. */
    wasi_io_streams_stream_error_free(error);
    return 1;
}

/* Clock and readiness are standard WASI resources. A timer expires even when
 * the server never responds. The caller owns and drops the readiness handle. */
static bool wait_ready(wasi_io_poll_own_pollable_t ready, uint64_t deadline, int *live, int *peak) {
    wasi_io_poll_own_pollable_t timer = wasi_clocks_monotonic_clock_subscribe_instant(deadline);
    if (++*live > *peak) *peak = *live;
    wasi_io_poll_borrow_pollable_t handles[] = {wasi_io_poll_borrow_pollable(ready), wasi_io_poll_borrow_pollable(timer)};
    wasi_io_poll_list_borrow_pollable_t inputs = {handles, 2};
    outbound_probe_list_u32_t completed;
    wasi_io_poll_poll(&inputs, &completed);
    bool available = false;
    for (size_t i = 0; i < completed.len; ++i) if (completed.ptr[i] == 0) available = true;
    outbound_probe_list_u32_free(&completed);
    wasi_io_poll_pollable_drop_own(timer);
    --*live;
    return available && wasi_clocks_monotonic_clock_now() < deadline;
}

static int32_t exchange(const uint8_t *authority, int32_t authority_len,
    const uint8_t *path, int32_t path_len, const uint8_t *token, int32_t token_len,
    int32_t post, const uint8_t *payload, int32_t payload_len, int32_t timeout_ms,
    uint8_t *output, int32_t capacity, int32_t *meta, int json) {
    memset(meta, 0, 6 * sizeof(int32_t)); meta[2] = -1;
    int result = 2, live = 0, peak = 0;
    RESOURCE(wasi_http_types_own_fields_t, fields);
    RESOURCE(wasi_http_types_own_outgoing_request_t, request);
    RESOURCE(wasi_http_types_own_outgoing_body_t, outgoing);
    RESOURCE(wasi_io_streams_own_output_stream_t, writer);
    RESOURCE(wasi_http_types_own_future_incoming_response_t, future);
    RESOURCE(wasi_io_poll_own_pollable_t, ready);
    RESOURCE(wasi_http_types_own_incoming_response_t, response);
    RESOURCE(wasi_http_types_own_fields_t, headers);
    RESOURCE(wasi_http_types_own_incoming_body_t, incoming);
    RESOURCE(wasi_io_streams_own_input_stream_t, reader);
    wasi_http_types_error_code_t error;
    wasi_io_streams_stream_error_t stream_error;
    if (capacity <= 0 || payload_len < 0 || payload_len > 4096 || timeout_ms <= 0) goto cleanup;

    fields = wasi_http_types_constructor_fields(); ACQUIRE(fields);
    outbound_probe_string_t key = {(uint8_t *)"x-probe-token", 13};
    outbound_probe_list_u8_t value = {(uint8_t *)token, token_len};
    wasi_http_types_header_error_t header_error;
    if (!wasi_http_types_method_fields_append(wasi_http_types_borrow_fields(fields), &key, &value, &header_error)) goto cleanup;
    if (json) {
        key = (outbound_probe_string_t){(uint8_t *)"accept", 6};
        value = (outbound_probe_list_u8_t){(uint8_t *)"application/json", 16};
        if (!wasi_http_types_method_fields_append(wasi_http_types_borrow_fields(fields), &key, &value, &header_error)) goto cleanup;
        if (post) {
            key = (outbound_probe_string_t){(uint8_t *)"content-type", 12};
            if (!wasi_http_types_method_fields_append(wasi_http_types_borrow_fields(fields), &key, &value, &header_error)) goto cleanup;
        }
    }
    char length[24]; snprintf(length, sizeof(length), "%d", post ? payload_len : 0);
    key = (outbound_probe_string_t){(uint8_t *)"content-length", 14};
    value = (outbound_probe_list_u8_t){(uint8_t *)length, strlen(length)};
    if (!wasi_http_types_method_fields_append(wasi_http_types_borrow_fields(fields), &key, &value, &header_error)) goto cleanup;
    request = wasi_http_types_constructor_outgoing_request(fields); TRANSFER(fields); ACQUIRE(request);
    wasi_http_types_borrow_outgoing_request_t req = wasi_http_types_borrow_outgoing_request(request);
    wasi_http_types_method_t method = {.tag = post ? WASI_HTTP_TYPES_METHOD_POST : WASI_HTTP_TYPES_METHOD_GET};
    wasi_http_types_scheme_t scheme = {.tag = WASI_HTTP_TYPES_SCHEME_HTTP};
    outbound_probe_string_t host = {(uint8_t *)authority, authority_len};
    outbound_probe_string_t target = {(uint8_t *)path, path_len};
    if (!wasi_http_types_method_outgoing_request_set_method(req, &method) ||
        !wasi_http_types_method_outgoing_request_set_scheme(req, &scheme) ||
        !wasi_http_types_method_outgoing_request_set_authority(req, &host) ||
        !wasi_http_types_method_outgoing_request_set_path_with_query(req, &target)) goto cleanup;
    if (!wasi_http_types_method_outgoing_request_body(req, &outgoing)) goto cleanup;
    ACQUIRE(outgoing);
    if (!wasi_http_types_method_outgoing_body_write(wasi_http_types_borrow_outgoing_body(outgoing), &writer)) goto cleanup;
    ACQUIRE(writer);
    uint64_t duration = (uint64_t) timeout_ms * 1000000;
    uint64_t deadline = wasi_clocks_monotonic_clock_now() + duration;
    /* Wasmtime 50.0.1 clones owned request-options without deleting the table
     * entry. Avoid that host leak: pass none and enforce deadlines with poll. */
#ifdef HTTP_PROBE_OWNED_OPTIONS
    /* Regression comparison for the pinned host: valid transfer of ownership
     * per WIT, but its implementation retains the options table entry. */
    wasi_http_types_own_request_options_t host_options = wasi_http_types_constructor_request_options();
    bool sent = wasi_http_outgoing_handler_handle(request, &host_options, &future, &error);
#else
    bool sent = wasi_http_outgoing_handler_handle(request, NULL, &future, &error);
#endif
    TRANSFER(request);
    if (!sent) { result = http_failure(&error, meta); goto cleanup; }
    ACQUIRE(future);
    if (post && payload_len) {
        outbound_probe_list_u8_t bytes = {(uint8_t *)payload, payload_len};
        if (!wasi_io_streams_method_output_stream_blocking_write_and_flush(wasi_io_streams_borrow_output_stream(writer), &bytes, &stream_error)) {
            result = stream_failure(&stream_error, meta); goto cleanup;
        }
    }
    DROP(writer, wasi_io_streams_output_stream_drop_own);
    bool finished = wasi_http_types_static_outgoing_body_finish(outgoing, NULL, &error);
    TRANSFER(outgoing);
    if (!finished) { result = http_failure(&error, meta); goto cleanup; }
    ready = wasi_http_types_method_future_incoming_response_subscribe(wasi_http_types_borrow_future_incoming_response(future)); ACQUIRE(ready);
    if (!wait_ready(ready, deadline, &live, &peak)) { result = 4; goto cleanup; }
    DROP(ready, wasi_io_poll_pollable_drop_own);
    wasi_http_types_result_result_own_incoming_response_error_code_void_t received;
    if (!wasi_http_types_method_future_incoming_response_get(wasi_http_types_borrow_future_incoming_response(future), &received) || received.is_err) goto cleanup;
    if (received.val.ok.is_err) { result = http_failure(&received.val.ok.val.err, meta); goto cleanup; }
    response = received.val.ok.val.ok; ACQUIRE(response);
    wasi_http_types_borrow_incoming_response_t resp = wasi_http_types_borrow_incoming_response(response);
    meta[0] = wasi_http_types_method_incoming_response_status(resp);
    headers = wasi_http_types_method_incoming_response_headers(resp); ACQUIRE(headers);
    key = (outbound_probe_string_t){(uint8_t *)"x-probe-token", 13};
    wasi_http_types_list_field_value_t values;
    wasi_http_types_method_fields_get(wasi_http_types_borrow_fields(headers), &key, &values);
    meta[5] = values.len == 1 && values.ptr[0].len == (size_t)token_len && memcmp(values.ptr[0].ptr, token, token_len) == 0;
    wasi_http_types_list_field_value_free(&values);
    DROP(headers, wasi_http_types_fields_drop_own);
    if (!wasi_http_types_method_incoming_response_consume(resp, &incoming)) goto cleanup;
    ACQUIRE(incoming);
    if (!wasi_http_types_method_incoming_body_stream(wasi_http_types_borrow_incoming_body(incoming), &reader)) goto cleanup;
    ACQUIRE(reader);
    ready = wasi_io_streams_method_input_stream_subscribe(wasi_io_streams_borrow_input_stream(reader)); ACQUIRE(ready);
    deadline = wasi_clocks_monotonic_clock_now() + duration;
    for (;;) {
        if (!wait_ready(ready, deadline, &live, &peak)) { result = 4; break; }
        outbound_probe_list_u8_t bytes;
        if (!wasi_io_streams_method_input_stream_read(wasi_io_streams_borrow_input_stream(reader), 4096, &bytes, &stream_error)) {
            if (stream_error.tag == WASI_IO_STREAMS_STREAM_ERROR_CLOSED) { result = 0; break; }
            result = stream_failure(&stream_error, meta); break;
        }
        if (bytes.len > (size_t)(capacity - meta[1])) {
            outbound_probe_list_u8_free(&bytes); result = 3; break;
        }
        memcpy(output + meta[1], bytes.ptr, bytes.len); meta[1] += bytes.len;
        if (bytes.len) deadline = wasi_clocks_monotonic_clock_now() + duration;
        outbound_probe_list_u8_free(&bytes);
    }
cleanup:
    /* Pollables are children of their stream/future. Drop them first. */
    DROP(ready, wasi_io_poll_pollable_drop_own);
    DROP(reader, wasi_io_streams_input_stream_drop_own);
    DROP(incoming, wasi_http_types_incoming_body_drop_own);
    DROP(headers, wasi_http_types_fields_drop_own);
    DROP(response, wasi_http_types_incoming_response_drop_own);
    DROP(writer, wasi_io_streams_output_stream_drop_own);
    DROP(outgoing, wasi_http_types_outgoing_body_drop_own);
#ifdef HTTP_PROBE_LEAK
    /* Negative control: lie to local accounting, leave the actual host handle live.
     * The independent Wasmtime resource limit must detect this leak. */
    if (have_future) TRANSFER(future);
#else
    DROP(future, wasi_http_types_future_incoming_response_drop_own);
#endif
    DROP(request, wasi_http_types_outgoing_request_drop_own);
    DROP(fields, wasi_http_types_fields_drop_own);
    meta[3] = live; meta[4] = peak;
    return result;
}

int32_t guicedee_http_exchange(const uint8_t *authority, int32_t authority_len,
    const uint8_t *path, int32_t path_len, const uint8_t *token, int32_t token_len,
    int32_t post, const uint8_t *payload, int32_t payload_len, int32_t timeout_ms,
    uint8_t *output, int32_t capacity, int32_t *meta) {
    return exchange(authority, authority_len, path, path_len, token, token_len,
        post, payload, payload_len, timeout_ms, output, capacity, meta, 0);
}
int32_t guicedee_http_json_exchange(const uint8_t *authority, int32_t authority_len,
    const uint8_t *path, int32_t path_len, const uint8_t *token, int32_t token_len,
    int32_t post, const uint8_t *payload, int32_t payload_len, int32_t timeout_ms,
    uint8_t *output, int32_t capacity, int32_t *meta) {
    return exchange(authority, authority_len, path, path_len, token, token_len,
        post, payload, payload_len, timeout_ms, output, capacity, meta, 1);
}
