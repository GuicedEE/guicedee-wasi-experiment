module com.guicedee.wasi.experiment {
    requires static teavm.interop;
    requires tools.jackson.databind;
    requires com.fasterxml.jackson.annotation;
    requires com.google.guice;
    requires jakarta.inject;
    exports com.guicedee.wasi.experiment;
    opens com.guicedee.wasi.experiment to com.google.guice, tools.jackson.databind;
}
