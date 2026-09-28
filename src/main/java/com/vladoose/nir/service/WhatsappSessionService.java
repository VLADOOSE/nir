package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.waha.WahaSession;
import com.vladoose.nir.integration.waha.WahaSessionManager;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappProviders;
import com.vladoose.nir.integration.whatsapp.WhatsappSource;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Страница «Система → WhatsApp» (спека whatsapp-waha §7): у WAHA — живой статус сессии (не ждёт минутного обновления),
 * QR, перезапуск и отвязка; у Green-API — последнее известное состояние (привязка — в его кабинете).
 */
@Service
public class WhatsappSessionService {

    private final WhatsappSource source;
    private final WhatsappStatusHolder status;
    private final WahaSessionManager sessions;
    private final boolean enabled;

    public WhatsappSessionService(WhatsappSource source, WhatsappStatusHolder status, WahaSessionManager sessions,
                                  @Value("${chats.whatsapp.enabled:false}") boolean enabled) {
        this.source = source;
        this.status = status;
        this.sessions = sessions;
        this.enabled = enabled;
    }

    public WhatsappSessionResponse info() {
        WhatsappSessionResponse r = new WhatsappSessionResponse();
        r.setProvider(source.name());
        r.setEnabled(enabled);
        r.setConfigured(source.isConfigured());
        r.setStatus(status.state());
        r.setNumber(status.number());
        if (!wahaActive()) return r;
        try {
            WahaSession s = sessions.live();
            r.setStatus(s == null ? null : s.status());
            r.setNumber(WahaSessionManager.number(s));
            r.setName(s == null ? null : s.mePushName());
            r.setQrAvailable(s != null && WahaSessionManager.SCAN_QR_CODE.equals(s.status()));
        } catch (GatewayException e) {
            r.setStatus(null);                  // прошлое «подключён» при неответившей WAHA было бы враньём
            r.setError(e.getMessage());
        }
        return r;
    }

    public byte[] qr() {
        requireWaha();
        try {
            return sessions.qr();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
    }

    public WhatsappSessionResponse restart() {
        requireWaha();
        try {
            sessions.restart();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
        return info();
    }

    public WhatsappSessionResponse logout() {
        requireWaha();
        try {
            sessions.logout();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
        return info();
    }

    private boolean wahaActive() {
        return enabled && WhatsappProviders.WAHA.equals(source.name()) && source.isConfigured();
    }

    private void requireWaha() {
        if (!wahaActive()) {
            throw new ConflictException("Управление номером доступно, когда приём WhatsApp включён через WAHA");
        }
    }
}
