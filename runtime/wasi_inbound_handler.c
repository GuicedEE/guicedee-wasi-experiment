#include "inbound.h"
#include "inbound_bridge.h"

#include <string.h>
#include <strings.h>

enum { MAX_REQUEST_BYTES = 256 };
static uint8_t request_body[MAX_REQUEST_BYTES];
static size_t request_length;
static bool handled_request;

int32_t inbound_request_size(void) {
    return (int32_t)request_length;
}

void inbound_copy_request(void *body, int32_t length) {
    if (length < 0 || (size_t)length != request_length) {
        __builtin_trap();
    }
    memcpy(body, request_body, request_length);
}
static int32_t response_status;
static uint8_t response_body[160];
static size_t response_length;

void inbound_set_response(int32_t status, const void *body, int32_t length) {
    if (response_status != 0 || status < 100 || status > 599
        || length < 0 || (size_t)length > sizeof(response_body)) {
        __builtin_trap();
    }
    memcpy(response_body, body, (size_t)length);
    response_length = (size_t)length;
    response_status = status;
}

static void send_error(wasi_http_types_own_response_outparam_t out) {
    wasi_http_types_result_own_outgoing_response_error_code_t result = {
        .is_err = true,
        .val.err.tag = WASI_HTTP_TYPES_ERROR_CODE_INTERNAL_ERROR,
    };
    wasi_http_types_static_response_outparam_set(out, &result);
}

static bool append_header(wasi_http_types_own_fields_t headers,
                          const char *name, const char *value) {
    inbound_string_t name_view;
    inbound_string_set(&name_view, name);
    wasi_http_types_field_value_t value_view = {
        .ptr = (uint8_t *)value, .len = strlen(value),
    };
    wasi_http_types_header_error_t error;
    return wasi_http_types_method_fields_append(
        wasi_http_types_borrow_fields(headers), &name_view, &value_view, &error);
}

static void send_json(wasi_http_types_own_response_outparam_t out) {
    inbound_list_u8_t body_bytes = {.ptr = response_body, .len = response_length};
    wasi_http_types_own_fields_t headers = wasi_http_types_constructor_fields();
    if (!append_header(headers, "content-type", "application/json")
        || !append_header(headers, "x-fixture-instance", handled_request ? "reused" : "first")
        || (response_status == 405 && !append_header(headers, "allow", "POST"))) {
        wasi_http_types_fields_drop_own(headers);
        send_error(out);
        return;
    }
    handled_request = true;

    wasi_http_types_own_outgoing_response_t response =
        wasi_http_types_constructor_outgoing_response(headers);
    wasi_http_types_borrow_outgoing_response_t borrowed_response =
        wasi_http_types_borrow_outgoing_response(response);
    wasi_http_types_own_outgoing_body_t body;
    if (!wasi_http_types_method_outgoing_response_set_status_code(
            borrowed_response, (uint16_t)response_status)
        || !wasi_http_types_method_outgoing_response_body(borrowed_response, &body)) {
        wasi_http_types_outgoing_response_drop_own(response);
        send_error(out);
        return;
    }

    wasi_io_streams_own_output_stream_t stream;
    if (!wasi_http_types_method_outgoing_body_write(
            wasi_http_types_borrow_outgoing_body(body), &stream)) {
        wasi_http_types_outgoing_body_drop_own(body);
        wasi_http_types_outgoing_response_drop_own(response);
        send_error(out);
        return;
    }

    wasi_http_types_result_own_outgoing_response_error_code_t result = {
        .val.ok = response,
    };
    wasi_http_types_static_response_outparam_set(out, &result);

    wasi_io_streams_stream_error_t write_error;
    bool written = wasi_io_streams_method_output_stream_blocking_write_and_flush(
        wasi_io_streams_borrow_output_stream(stream), &body_bytes, &write_error);
    wasi_io_streams_output_stream_drop_own(stream);
    if (!written) {
        wasi_io_streams_stream_error_free(&write_error);
        wasi_http_types_outgoing_body_drop_own(body);
        return;
    }

    wasi_http_types_error_code_t finish_error;
    if (!wasi_http_types_static_outgoing_body_finish(body, NULL, &finish_error)) {
        wasi_http_types_error_code_free(&finish_error);
    }
}

static bool field_matches(wasi_http_types_field_value_t field, const char *literal) {
    return field.len == strlen(literal)
        && strncasecmp((const char *)field.ptr, literal, field.len) == 0;
}

static bool json_content_type(wasi_http_types_borrow_incoming_request_t request) {
    wasi_http_types_own_fields_t headers = wasi_http_types_method_incoming_request_headers(request);
    inbound_string_t name;
    inbound_string_set(&name, "content-type");
    wasi_http_types_list_field_value_t values;
    wasi_http_types_method_fields_get(wasi_http_types_borrow_fields(headers), &name, &values);
    bool matches = values.len == 1 && (field_matches(values.ptr[0], "application/json")
        || field_matches(values.ptr[0], "application/json; charset=utf-8"));
    wasi_http_types_list_field_value_free(&values);
    wasi_http_types_fields_drop_own(headers);
    return matches;
}

static int read_body(wasi_http_types_borrow_incoming_request_t request) {
    wasi_http_types_own_incoming_body_t body;
    if (!wasi_http_types_method_incoming_request_consume(request, &body)) return 400;

    wasi_io_streams_own_input_stream_t stream;
    if (!wasi_http_types_method_incoming_body_stream(
            wasi_http_types_borrow_incoming_body(body), &stream)) {
        wasi_http_types_incoming_body_drop_own(body);
        return 400;
    }

    int status;
    for (;;) {
        size_t room = sizeof(request_body) - request_length;
        inbound_list_u8_t bytes;
        wasi_io_streams_stream_error_t error;
        if (!wasi_io_streams_method_input_stream_blocking_read(
                wasi_io_streams_borrow_input_stream(stream), room + 1, &bytes, &error)) {
            status = error.tag == WASI_IO_STREAMS_STREAM_ERROR_CLOSED ? 200 : 400;
            wasi_io_streams_stream_error_free(&error);
            break;
        }
        if (bytes.len > room) {
            inbound_list_u8_free(&bytes);
            status = 413;
            break;
        }
        if (bytes.len != 0) {
            memcpy(request_body + request_length, bytes.ptr, bytes.len);
            request_length += bytes.len;
        }
        inbound_list_u8_free(&bytes);
    }
    wasi_io_streams_input_stream_drop_own(stream);
    wasi_http_types_incoming_body_drop_own(body);
    return status;
}

static void handle_request(wasi_http_types_borrow_incoming_request_t request) {
    inbound_string_t path;
    bool has_path = wasi_http_types_method_incoming_request_path_with_query(request, &path);
    bool accepted_path = has_path && path.len == sizeof("/calculate") - 1
        && memcmp(path.ptr, "/calculate", path.len) == 0;
    if (has_path) inbound_string_free(&path);

    if (!accepted_path) {
        static const char missing[] = "{\"error\":\"not_found\"}";
        inbound_set_response(404, missing, sizeof(missing) - 1);
        return;
    }

    wasi_http_types_method_t method;
    wasi_http_types_method_incoming_request_method(request, &method);
    bool is_post = method.tag == WASI_HTTP_TYPES_METHOD_POST;
    wasi_http_types_method_free(&method);
    if (!is_post) {
        static const char wrong_method[] = "{\"error\":\"method_not_allowed\"}";
        inbound_set_response(405, wrong_method, sizeof(wrong_method) - 1);
        return;
    }
    if (!json_content_type(request)) {
        static const char wrong_type[] = "{\"error\":\"unsupported_media_type\"}";
        inbound_set_response(415, wrong_type, sizeof(wrong_type) - 1);
        return;
    }

    int status = read_body(request);
    if (status == 413) {
        static const char oversized[] = "{\"error\":\"body_too_large\",\"max_bytes\":256}";
        inbound_set_response(413, oversized, sizeof(oversized) - 1);
    } else if (status != 200) {
        static const char failed[] = "{\"error\":\"invalid_body\"}";
        inbound_set_response(400, failed, sizeof(failed) - 1);
    }
}

void exports_wasi_http_incoming_handler_handle(
    exports_wasi_http_incoming_handler_own_incoming_request_t request,
    exports_wasi_http_incoming_handler_own_response_outparam_t out) {
    request_length = 0;
    response_status = 0;
    handle_request(wasi_http_types_borrow_incoming_request(request));
    wasi_http_types_incoming_request_drop_own(request);

    if (response_status == 0) {
        char program[] = "inbound-json";
        char *argv[] = {program, NULL};
        if (inbound_dispatch(1, argv) != 0 || response_status == 0) {
            static const char failed[] = "{\"error\":\"internal_error\"}";
            response_status = 0;
            inbound_set_response(500, failed, sizeof(failed) - 1);
        }
    }
    send_json(out);
}
