package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTextTest {

    @Test
    void usesTheMessageWhenThereIsOne() {
        assertThat(ErrorText.of(new IOException("request timed out"))).isEqualTo("request timed out");
    }

    /** HttpClient JDK 17 при отказе в соединении бросает ConnectException без текста — в логе было «недоступно: null». */
    @Test
    void namesTheExceptionClassWhenThereIsNoMessage() {
        assertThat(ErrorText.of(new ConnectException())).isEqualTo("ConnectException");
    }

    @Test
    void blankMessageCountsAsMissing() {
        assertThat(ErrorText.of(new IOException("  "))).isEqualTo("IOException");
    }

    /** Текст идёт в тост оператору: имена Java-классов в нём — шум, а длинное слово без пробелов распирает тост. */
    @Test
    void dropsJavaClassNamesFromTheMessage() {
        var tls = new javax.net.ssl.SSLHandshakeException("PKIX path building failed: "
                + "sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target");

        assertThat(ErrorText.of(tls))
                .isEqualTo("PKIX path building failed: unable to find valid certification path to requested target");
    }

    @Test
    void keepsOrdinaryDottedTextIntact() {
        assertThat(ErrorText.of(new IOException("fms.ecc.kz вернул 503 для https://fms.ecc.kz/ru/searchanno")))
                .isEqualTo("fms.ecc.kz вернул 503 для https://fms.ecc.kz/ru/searchanno");
    }
}
