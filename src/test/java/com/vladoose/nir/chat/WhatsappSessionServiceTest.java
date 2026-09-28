package com.vladoose.nir.chat;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.waha.FakeWahaClient;
import com.vladoose.nir.integration.waha.WahaSession;
import com.vladoose.nir.integration.waha.WahaSessionManager;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappSource;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import com.vladoose.nir.service.WhatsappSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;

class WhatsappSessionServiceTest {

    final FakeWahaClient fake = new FakeWahaClient();
    final WhatsappStatusHolder status = new WhatsappStatusHolder();
    final WahaSessionManager sessions = new WahaSessionManager(fake, "westmed", "KZ");
    final WhatsappSource waha = source("waha", true);

    static WhatsappSource source(String name, boolean configured) {
        WhatsappSource s = Mockito.mock(WhatsappSource.class);
        Mockito.when(s.name()).thenReturn(name);
        Mockito.when(s.isConfigured()).thenReturn(configured);
        return s;
    }

    WhatsappSessionService service(WhatsappSource source, boolean enabled) {
        return new WhatsappSessionService(source, status, sessions, enabled);
    }

    @Test
    void waitingForLinkShowsQr() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);

        WhatsappSessionResponse r = service(waha, true).info();

        assertThat(r.getProvider()).isEqualTo("waha");
        assertThat(r.getStatus()).isEqualTo("SCAN_QR_CODE");
        assertThat(r.isQrAvailable()).isTrue();
        assertThat(r.getNumber()).isNull();
    }

    @Test
    void linkedNumberAndNameAreShown() {
        fake.session = new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med");

        WhatsappSessionResponse r = service(waha, true).info();

        assertThat(r.getStatus()).isEqualTo("WORKING");
        assertThat(r.getNumber()).isEqualTo("77000000001");
        assertThat(r.getName()).isEqualTo("West-Med");
        assertThat(r.isQrAvailable()).isFalse();
    }

    /** WAHA не ответила — страница показывает причину, а не падает. */
    @Test
    void unreachableWahaIsErrorTextNotFailure() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");

        assertThat(service(waha, true).info().getError()).contains("WAHA недоступен");
    }

    @Test
    void actionsNeedEnabledWaha() {
        assertThatThrownBy(() -> service(waha, false).restart()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service(source("greenapi", true), true).logout()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service(source("waha", false), true).qr()).isInstanceOf(ConflictException.class);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void restartLogoutAndQrGoToWaha() {
        fake.session = new WahaSession("westmed", "FAILED", "77000000001@c.us", "West-Med");
        WhatsappSessionService s = service(waha, true);

        s.restart();
        s.logout();
        byte[] qr = s.qr();

        assertThat(fake.calls).contains("restart westmed", "logout westmed", "qr westmed");
        assertThat(qr).isEqualTo(fake.qr);
    }

    @Test
    void wahaErrorOnActionIsBadGateway() {
        fake.failWith = new GatewayException(422, "WAHA: HTTP 422 при получении QR-кода");

        assertThatThrownBy(() -> service(waha, true).qr()).isInstanceOf(UpstreamException.class).hasMessageContaining("422");
    }

    /** Green-API: привязка — в его кабинете; страница показывает последнее известное состояние и в WAHA не ходит. */
    @Test
    void greenApiShowsLastKnownState() {
        status.setState("authorized");
        status.setNumber("77000000001@c.us");

        WhatsappSessionResponse r = service(source("greenapi", true), true).info();

        assertThat(r.getStatus()).isEqualTo("authorized");
        assertThat(r.getNumber()).isEqualTo("77000000001");
        assertThat(fake.calls).isEmpty();
    }
}
