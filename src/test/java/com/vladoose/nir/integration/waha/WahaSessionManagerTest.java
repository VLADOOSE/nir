package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WahaSessionManagerTest {

    final FakeWahaClient fake = new FakeWahaClient();
    final WhatsappStatusHolder status = new WhatsappStatusHolder();
    final WahaSessionManager sessions = new WahaSessionManager(fake, "westmed", "kz");

    static WahaSession working() { return new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med"); }

    @Test
    void missingSessionIsCreatedWithMarketAtFirstRefresh() {
        sessions.refresh(status);

        assertThat(fake.calls).contains("create westmed KZ");
        assertThat(status.snapshot(true, true).getState()).isEqualTo("SCAN_QR_CODE");
    }

    /** Остановленную сессию запускаем только при старте АИС: остановку потом мог сделать человек. */
    @Test
    void stoppedSessionIsStartedOnlyOnce() {
        fake.session = new WahaSession("westmed", "STOPPED", null, null);
        sessions.refresh(status);
        fake.session = new WahaSession("westmed", "STOPPED", null, null);
        sessions.refresh(status);

        assertThat(fake.count("start ")).isEqualTo(1);
    }

    @Test
    void workingSessionPublishesNumberAndAccount() {
        fake.session = working();

        sessions.refresh(status);

        assertThat(status.snapshot(true, true).getNumber()).isEqualTo("77000000001");
        assertThat(sessions.account()).isEqualTo("77000000001");
    }

    @Test
    void becomingWorkingIsReportedOncePerTransition() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = working();
        assertThat(sessions.refresh(status)).isTrue();
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = new WahaSession("westmed", "FAILED", "77000000001@c.us", "West-Med");
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = working();
        assertThat(sessions.refresh(status)).isTrue();
    }

    /** Номер отвязали — старый номер не должен оставаться ни в строке состояния, ни в account чатов. */
    @Test
    void unlinkedSessionClearsNumber() {
        fake.session = working();
        sessions.refresh(status);
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        sessions.refresh(status);

        assertThat(status.snapshot(true, true).getNumber()).isNull();
        assertThat(sessions.account()).isNull();
    }

    /** WAHA поднялась позже АИС: сессия создаётся при первом успешном обращении, а не теряется. */
    @Test
    void unreachableWahaPropagatesAndCreationIsRetried() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");
        assertThatThrownBy(() -> sessions.refresh(status)).isInstanceOf(GatewayException.class);

        fake.failWith = null;
        sessions.refresh(status);

        assertThat(fake.calls).contains("create westmed KZ");
    }
}
