module com.guicedee.wasi.experiment {
    requires com.google.guice;
    requires jakarta.inject;
    exports com.guicedee.wasi.experiment;
    opens com.guicedee.wasi.experiment to com.google.guice;
}
