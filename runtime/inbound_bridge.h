#ifndef GUICE_INBOUND_BRIDGE_H
#define GUICE_INBOUND_BRIDGE_H

#include <stdint.h>

int32_t inbound_request_size(void);
void inbound_copy_request(void *body, int32_t length);
void inbound_set_response(int32_t status, const void *body, int32_t length);
int inbound_dispatch(int argc, char **argv);

#endif
