package com.vladoose.nir.integration.waha;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class WahaSignatureTest {

    /** Эталон из документации WAHA (Events → HMAC); сверен openssl при написании плана. */
    static final String DOC_BODY = "{\"event\":\"message\",\"session\":\"default\",\"engine\":\"WEBJS\"}";
    static final String DOC_HMAC = "208f8a55dde9e05519e898b10b89bf0d0b3b0fdf11fdbf09b6b90476301b98d8"
            + "097c462b2b17a6ce93b6b47a136cf2e78a33a63f6752c2c1631777076153fa89";

    @Test
    void documentationVectorMatches() {
        byte[] body = DOC_BODY.getBytes(StandardCharsets.UTF_8);

        assertThat(WahaSignature.sign(body, "my-secret-key")).isEqualTo(DOC_HMAC);
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC)).isTrue();
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC.toUpperCase())).isTrue();
    }

    @Test
    void anythingElseIsRejected() {
        byte[] body = DOC_BODY.getBytes(StandardCharsets.UTF_8);

        assertThat(WahaSignature.verify(body, "other-key", DOC_HMAC)).isFalse();
        assertThat(WahaSignature.verify((DOC_BODY + " ").getBytes(StandardCharsets.UTF_8), "my-secret-key", DOC_HMAC)).isFalse();
        assertThat(WahaSignature.verify(body, "my-secret-key", "zz-не-hex")).isFalse();
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC.substring(2))).isFalse();
        assertThat(WahaSignature.verify(body, "", DOC_HMAC)).isFalse();          // ключ не задан — не принимаем ничего
        assertThat(WahaSignature.verify(body, "my-secret-key", null)).isFalse();
        assertThat(WahaSignature.verify(null, "my-secret-key", DOC_HMAC)).isFalse();
    }
}
