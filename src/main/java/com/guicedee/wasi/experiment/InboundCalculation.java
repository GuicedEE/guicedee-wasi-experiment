package com.guicedee.wasi.experiment;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Stage;
import com.guicedee.wasi.runtime.InboundExchange;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.json.JsonFactory;

/** One synchronous JSON operation behind the standalone WASI HTTP reactor. */
public final class InboundCalculation {
    private static Handler handler;

    public static final class Limits {
        private final int magnitude;

        Limits(int magnitude) {
            this.magnitude = magnitude;
        }
    }

    public static final class Calculator {
        private final Limits limits;

        @Inject
        public Calculator(Limits limits) {
            this.limits = limits;
        }

        String calculate(int value) {
            int used = Math.max(-limits.magnitude, Math.min(limits.magnitude, value));
            return "{\"result\":" + used * 2 + ",\"used\":" + used
                + ",\"clamped\":" + (used != value) + "}";
        }
    }

    private record Reply(int status, String json) { }

    public static final class Handler {
        private final Calculator calculator;
        private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build())
            .build();

        @Inject
        public Handler(Calculator calculator) {
            this.calculator = calculator;
        }

        private static final Reply MALFORMED = new Reply(400, "{\"error\":\"malformed_json\"}");
        private static final Reply INVALID = new Reply(422, "{\"error\":\"invalid_input\"}");

        Reply handle(byte[] body) {
            // A literal NUL is illegal in JSON. Reject it before Jackson's
            // alternate encoding detection on an application/json request.
            for (byte value : body) {
                if (value == 0) return MALFORMED;
            }
            try (var parser = json.createParser(ObjectReadContext.empty(), body)) {
                JsonToken start = parser.nextToken();
                if (start == null) return MALFORMED;
                if (start != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    return parser.nextToken() == null ? INVALID : MALFORMED;
                }

                int fields = 0;
                Integer value = null;
                for (JsonToken field = parser.nextToken(); field != JsonToken.END_OBJECT; field = parser.nextToken()) {
                    if (field != JsonToken.PROPERTY_NAME) return MALFORMED;
                    boolean isValue = "value".equals(parser.currentName());
                    JsonToken token = parser.nextToken();
                    fields++;
                    if (fields == 1 && isValue && token == JsonToken.VALUE_NUMBER_INT) {
                        try {
                            value = parser.getIntValue();
                        } catch (InputCoercionException outOfRange) {
                            // Keep reading so syntax errors take precedence.
                        }
                    }
                    parser.skipChildren();
                }
                if (parser.nextToken() != null) return MALFORMED;
                if (fields != 1 || value == null) return INVALID;
                return new Reply(200, calculator.calculate(value));
            } catch (JacksonException malformed) {
                return MALFORMED;
            }
        }
    }

    public static final class Bindings extends AbstractModule {
        @Override
        protected void configure() {
            binder().disableCircularProxies();
            bind(Limits.class).toInstance(new Limits(100));
            bind(Calculator.class);
            bind(Handler.class);
        }
    }

    public static void main(String[] args) {
        if (handler == null) {
            handler = Guice.createInjector(Stage.DEVELOPMENT, new Bindings()).getInstance(Handler.class);
        }
        Reply reply = handler.handle(InboundExchange.read());
        InboundExchange.send(reply.status(), reply.json());
    }
}
