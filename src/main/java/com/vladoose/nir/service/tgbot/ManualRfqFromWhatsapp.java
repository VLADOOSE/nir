package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.integration.whatsapp.OutgoingFileListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Исходящий файл «Запрос КП № N от …» в зеркале WhatsApp — запрос цен N, ждущий подтверждения после «📱 Отправлю сам», передан
 * (спека 2026-10-21-tender-telegram-bot, доработка оператора). Имя файла WhatsApp не меняет; копия «… (1).pdf» — тоже.
 */
@Component
public class ManualRfqFromWhatsapp implements OutgoingFileListener {

    /** Подтверждение запроса цен по номеру; чат — имя переписки, нет — телефон. */
    public interface Confirm {
        void confirm(long priceRequestId, String chat, OffsetDateTime at);
    }

    private static final Pattern NUMBER = Pattern.compile("Запрос КП № (\\d{1,18})");

    private final Confirm confirm;

    @Autowired
    public ManualRfqFromWhatsapp(BotLetterActions actions) {
        this(actions::confirmFromWhatsapp);
    }

    public ManualRfqFromWhatsapp(Confirm confirm) {
        this.confirm = confirm;
    }

    @Override
    public void outgoingFile(String fileName, String chatName, String phone, OffsetDateTime sentAt) {
        if (fileName == null) return;
        Matcher m = NUMBER.matcher(fileName);
        if (!m.find()) return;
        confirm.confirm(Long.parseLong(m.group(1)), chatName != null && !chatName.isBlank() ? chatName : phone, sentAt);
    }
}
