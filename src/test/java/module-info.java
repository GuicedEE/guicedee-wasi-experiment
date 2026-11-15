module com.guicedee.wasi.experiment.test {
    requires com.guicedee.wasi.experiment;
    requires org.junit.jupiter.api;
    opens com.guicedee.wasi.experiment.test to org.junit.platform.commons;
}
