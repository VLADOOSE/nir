package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Сессия WAHA = рабочий номер (спека whatsapp-waha §7). Создаёт или запускает её сама при первом успешном обращении
 * после старта АИС (WAHA может подняться позже бэкенда), раз в минуту из потока приёма обновляет статус и номер.
 * Страница «Система → WhatsApp» зовёт live/qr/restart/logout из потоков HTTP: last — volatile, ensured — только поток приёма.
 */
@Component
public class WahaSessionManager {

    public static final String WORKING = "WORKING";
    public static final String SCAN_QR_CODE = "SCAN_QR_CODE";
    static final String STOPPED = "STOPPED";

    private final WahaClient client;
    private final String session;
    private final String market;
    private volatile WahaSession last;
    private boolean ensured;

    public WahaSessionManager(WahaClient client,
                              @Value("${chats.whatsapp.waha.session:westmed}") String session,
                              @Value("${chats.whatsapp.market:KZ}") String market) {
        this.client = client;
        this.session = session == null || session.isBlank() ? "westmed" : session.strip();
        this.market = market == null ? "KZ" : market.strip().toUpperCase(Locale.ROOT);
    }

    public String session() { return session; }

    /** Номер подключённого WhatsApp без «@c.us» — account чатов; null — не привязан или ещё не спрашивали. */
    public String account() { return number(last); }

    /** «77000000001@c.us» → «77000000001»; номера нет → null. */
    public static String number(WahaSession s) {
        if (s == null || s.meId() == null) return null;
        String digits = WahaEventParser.userOf(s.meId());
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Из потока приёма, раз в минуту. Сессии нет — создаёт её в любой момент (первый старт или WAHA потеряла
     * хранилище: без сессии нет ни приёма, ни QR, а исправить это со страницы нечем). Остановленную запускает только
     * при первом успешном вызове после старта АИС: позже остановку мог сделать человек. true — сессия только что стала
     * WORKING: повод догнать пропущенное (спека §5.3).
     */
    public boolean refresh(WhatsappStatusHolder status) {
        WahaSession s = client.session(session);
        if (s == null) {
            client.createSession(session, market);
            s = client.session(session);
        } else if (!ensured && STOPPED.equals(s.status())) {
            client.startSession(session);
            s = client.session(session);
        }
        ensured = true;
        boolean wasWorking = last != null && WORKING.equals(last.status());
        last = s;
        status.setState(s == null ? null : s.status());
        status.setNumber(s == null ? null : s.meId());
        return s != null && WORKING.equals(s.status()) && !wasWorking;
    }

    /** Свежий статус для страницы (не ждёт минутного обновления); last не трогает — им владеет поток приёма. */
    public WahaSession live() { return client.session(session); }

    public byte[] qr() { return client.qrPng(session); }

    public void restart() { client.restartSession(session); }

    public void logout() { client.logoutSession(session); }
}
