package com.vladoose.nir.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TlsDefaultsTest {

    private static final String AIA = "com.sun.security.enableAIAcaIssuers";

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty(AIA);
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(AIA);
        else System.setProperty(AIA, saved);
    }

    @Test
    void turnsAiaFetchingOnWhenNothingIsConfigured() {
        System.clearProperty(AIA);

        TlsDefaults.enableAiaFetching();

        assertThat(System.getProperty(AIA)).isEqualTo("true");
    }

    /** Запасной выход для эксплуатации: -Dcom.sun.security.enableAIAcaIssuers=false в JAVA_OPTS без правки кода. */
    @Test
    void keepsAnExplicitChoiceFromTheCommandLine() {
        System.setProperty(AIA, "false");

        TlsDefaults.enableAiaFetching();

        assertThat(System.getProperty(AIA)).isEqualTo("false");
    }

    @Test
    void loadingTheApplicationClassTurnsAiaFetchingOn() throws ClassNotFoundException {
        Class.forName("com.vladoose.nir.Nir2Application", true, getClass().getClassLoader());

        assertThat(System.getProperty(AIA)).isEqualTo("true");
    }
}
