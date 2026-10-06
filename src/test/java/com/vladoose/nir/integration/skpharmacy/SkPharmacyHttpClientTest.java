package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.exception.UpstreamException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkPharmacyHttpClientTest {

    @Test
    void unreachablePortalNamesTheFailureInsteadOfNull() throws IOException {
        SkPharmacyHttpClient client = new SkPharmacyHttpClient("http://127.0.0.1:" + closedPort());

        assertThatThrownBy(() -> client.searchPage(1))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Сеть fms.ecc.kz: ConnectException");
    }

    /** Кнопка «ТЗ» у лота СК-Фармации показывает этот текст оператору. */
    @Test
    void unreachablePortalOnTechSpecNamesTheFailureInsteadOfNull() throws IOException {
        SkTechSpecHttpClient client = new SkTechSpecHttpClient("http://127.0.0.1:" + closedPort());

        assertThatThrownBy(() -> client.fetchTechSpecRefs("521464"))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Сеть fms.ecc.kz: ConnectException");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
