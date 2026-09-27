package com.vladoose.nir.gate;

import com.vladoose.nir.controller.GateController;
import com.vladoose.nir.dto.request.GateAccessRequest;
import com.vladoose.nir.dto.response.GateStateResponse;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class GateControllerTest {

    @Autowired GateController controller;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private static final String EDGE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.68";

    private ResponseEntity<GateStateResponse> ask(String token, String name) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Forwarded-For", "9.9.9.9, 5.6.7.8, 172.18.0.1");
        GateAccessRequest body = new GateAccessRequest();
        body.setName(name);
        return controller.request(token, body, EDGE, http);
    }

    private static String tokenFrom(ResponseEntity<?> r) {
        String c = r.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return c.substring(c.indexOf('=') + 1, c.indexOf(';'));
    }

    @Test
    void requestHandsOutProtectedCookie() {
        ResponseEntity<GateStateResponse> r = ask(null, "Асель");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .startsWith("ais_device=")
                .contains("Path=/", "Max-Age=34560000", "Secure", "HttpOnly", "SameSite=Lax");
        assertThat(r.getBody().getState()).isEqualTo("PENDING");
        TrustedDevice d = repo.findByTokenHash(DeviceTokens.hash(tokenFrom(r))).orElseThrow();
        assertThat(d.getIp()).isEqualTo("5.6.7.8");       // адрес, дописанный nginx хоста, а не присланный клиентом
        assertThat(d.getUserAgent()).contains("Edg/");
    }

    @Test
    void repeatRequestDoesNotReplaceCookie() {
        String token = tokenFrom(ask(null, "Асель"));

        ResponseEntity<GateStateResponse> again = ask(token, "Асель");

        assertThat(again.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
        assertThat(again.getBody().getState()).isEqualTo("PENDING");
    }

    @Test
    void checkAnswers204OnlyForTrustedDevice() {
        assertThat(controller.check(null).getStatusCode().value()).isEqualTo(401);
        String token = tokenFrom(ask(null, "Асель"));
        assertThat(controller.check(token).getStatusCode().value()).isEqualTo(401);

        Long id = repo.findByTokenHash(DeviceTokens.hash(token)).orElseThrow().getId();
        gate.approve(id, null, "admin1", OffsetDateTime.now());

        ResponseEntity<Void> ok = controller.check(token);
        assertThat(ok.getStatusCode().value()).isEqualTo(204);
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void statusWithoutCookieIsNone() {
        assertThat(controller.status(null).getBody().getState()).isEqualTo("NONE");
    }
}
