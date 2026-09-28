package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.integration.greenapi.GreenApiSource;
import com.vladoose.nir.integration.waha.WahaInboxSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Какой источник уведомлений работает — chats.whatsapp.provider (спека whatsapp-waha §1, решение 2). Опечатка АИС
 * не роняет (как уронил бы @ConditionalOnProperty без подходящего бина): приём стоит с красной строкой — тот же
 * урок, что с опечаткой в WHATSAPP_MARKET.
 */
@Configuration
public class WhatsappSourceConfig {

    /** destroyMethod = "": бин — тот же объект, что источник-компонент, закрывать его вторым именем незачем. */
    @Bean(destroyMethod = "")
    @Primary
    public WhatsappSource whatsappSource(@Value("${chats.whatsapp.provider:waha}") String provider,
                                         GreenApiSource greenApi, WahaInboxSource waha) {
        return select(provider, greenApi, waha);
    }

    static WhatsappSource select(String provider, GreenApiSource greenApi, WahaInboxSource waha) {
        String p = WhatsappProviders.normalize(provider);
        if (p.equals(WhatsappProviders.WAHA)) return waha;
        if (p.equals(WhatsappProviders.GREENAPI)) return greenApi;
        return new UnknownProviderSource(provider);
    }

    /** Источник для опечатки в WHATSAPP_PROVIDER: ничего не принимает, строка состояния объясняет. */
    static final class UnknownProviderSource implements WhatsappSource {

        private final String raw;

        UnknownProviderSource(String raw) { this.raw = raw == null ? "" : raw; }

        @Override public String name() { return WhatsappProviders.normalize(raw); }
        @Override public boolean isConfigured() { return false; }
        @Override public String configHint() {
            return "WHATSAPP_PROVIDER: неизвестный провайдер «" + raw + "» — приём остановлен (нужно waha или greenapi)";
        }
        @Override public String waitingNote() { return "сообщения ждут на стороне шлюза"; }
        @Override public void housekeeping(WhatsappStatusHolder status) { }
        @Override public WhatsappNotification next() { return null; }
        @Override public ParsedNotification parse(WhatsappNotification n) { return new ParsedNotification.Skip("нет источника"); }
        @Override public void ack(WhatsappNotification n, String droppedReason) { }
        @Override public byte[] download(FileRef ref, long maxBytes) { throw new GatewayException(0, configHint()); }
    }
}
