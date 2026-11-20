"""Independent HTTP observations for the Guice/Jackson composition gate."""
import base64
from collections import Counter
import json
from run_http_probe import Fixture

TYPED_MARKERS = [
    'WASI_JACKSON_OK dto annotation generic-list unicode null malformed recovery',
    'WASI_TYPED_HTTP_INJECTION_OK named provider singleton full-generic-types',
    'WASI_TYPED_HTTP_FAILURES_OK prepare encode status decode timeout size stream recovery',
    'WASI_TYPED_HTTP_CLEANUP_OK calls=150',
    'WASI_TYPED_HTTP_OK jackson3 real-guice get post errors cleanup',
]
GET_PATH = '/dto/caf%C3%A9%20%2F%3F?search=a%20b%26c'
ITEM = {'display_name': 'WASI café ☕', 'quantity': 42, 'tags': ['edge', 'java'],
        'price': 15.5, 'sequence': 9007199254740993,
        'detail': {'active': True, 'note': 'quote " slash \\ newline\n'}}

class TypedFixture(Fixture):
    def response(self, path, body):
        if path == '/malformed': return 200, b'{'
        if path == '/wrong-shape': return 200, b'[]'
        value = [ITEM, {**ITEM, 'quantity': 7}] if path == '/list' else ITEM
        return 200, json.dumps(value, ensure_ascii=False).encode()

    def verify(self):
        counts = Counter(row['path'] for row in self.requests)
        expected = {GET_PATH: 75, '/post': 1, '/list': 1, '/empty': 1,
            **{path: 9 for path in ('/status/404', '/status/500', '/malformed', '/wrong-shape',
                                  '/slow-headers', '/slow-body', '/large', '/truncated')}}
        if dict(counts) != expected: raise RuntimeError(f'Typed request counts differ: {counts}')
        for row in self.requests:
            headers = {k.lower(): v for k, v in row['headers'].items()}
            if headers.get('x-probe-token') != self.token or headers.get('accept') != 'application/json':
                raise RuntimeError('Typed request headers did not reach the server')
            body = base64.b64decode(row['body_base64'])
            if row['path'] == '/post':
                if row['method'] != 'POST' or json.loads(body) != ITEM or headers.get('content-type') != 'application/json':
                    raise RuntimeError('Typed POST wire representation differs')
            elif row['method'] != 'GET' or body:
                raise RuntimeError('Typed GET method/body differs')
        return dict(counts)
