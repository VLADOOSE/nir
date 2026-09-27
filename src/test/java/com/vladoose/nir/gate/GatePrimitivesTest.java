package com.vladoose.nir.gate;

import com.vladoose.nir.util.ClientIp;
import com.vladoose.nir.util.DeviceTokens;
import com.vladoose.nir.util.GateCookie;
import com.vladoose.nir.util.UserAgentSummary;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;

class GatePrimitivesTest {

    @Test
    void tokenIs43UrlSafeRandomChars() {
        String a = DeviceTokens.newToken();
        String b = DeviceTokens.newToken();
        assertThat(a).matches("[A-Za-z0-9_-]{43}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(DeviceTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void codeUsesOnlyUnambiguousCharacters() {
        for (int i = 0; i < 500; i++) assertThat(DeviceTokens.newCode()).matches("[2-9A-HJKMNP-Z]{6}");
        assertThat(DeviceTokens.CODE_ALPHABET).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void codeIsShownInTwoHalves() {
        assertThat(DeviceTokens.display("7K4QM2")).isEqualTo("7K4-QM2");
    }

    @Test
    void userAgentGoldenSet() {
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo("iPhone · Safari");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1"))
                .isEqualTo("iPhone · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"))
                .isEqualTo("Android · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Linux; Android 13; M2101K6G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 YaBrowser/24.4.1.99.00 SA/3 Mobile Safari/537.36"))
                .isEqualTo("Android · Яндекс");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo("Mac · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"))
                .isEqualTo("Mac · Safari");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.68"))
                .isEqualTo("Windows · Edge");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"))
                .isEqualTo("Windows · Firefox");
        assertThat(UserAgentSummary.describe(null)).isEqualTo("Браузер");
        assertThat(UserAgentSummary.describe("curl/8.4.0")).isEqualTo("Браузер");
    }

    @Test
    void clientIpIsTheAddressAddedByHostNginx() {
        assertThat(ip("1.2.3.4, 5.6.7.8, 172.18.0.1", "172.18.0.5")).isEqualTo("5.6.7.8");   // первый — подделка клиента
        assertThat(ip("5.6.7.8, 172.18.0.1", "172.18.0.5")).isEqualTo("5.6.7.8");
        assertThat(ip("172.18.0.1", "172.18.0.5")).isEqualTo("172.18.0.1");
        assertThat(ip(null, "10.0.0.7")).isEqualTo("10.0.0.7");
    }

    private static String ip(String xff, String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        r.setRemoteAddr(remote);
        return ClientIp.of(r);
    }

    @Test
    void cookieIsProtectedAndLongLived() {
        assertThat(GateCookie.of("abc").toString())
                .startsWith("ais_device=abc")
                .contains("Path=/", "Max-Age=34560000", "Secure", "HttpOnly", "SameSite=Lax");
    }
}
