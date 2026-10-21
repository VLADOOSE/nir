package com.vladoose.nir.tgbot;

import com.vladoose.nir.service.tgbot.ManualRfqFromWhatsapp;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Имя файла из зеркала WhatsApp → номер запроса цен «передам сам» (кнопка бота «📱 Отправлю сам»). */
class ManualRfqFromWhatsappTest {

    @Test
    void numberFromFileName_chatLabel() {
        List<String> got = new ArrayList<>();
        ManualRfqFromWhatsapp l = new ManualRfqFromWhatsapp((prId, chat, at) -> got.add(prId + " | " + chat));
        OffsetDateTime now = OffsetDateTime.now();
        l.outgoingFile("Запрос КП № 145 от 21.10.2026 – West-Med.pdf", "ZZ Аксион", "+77011234567", now);
        l.outgoingFile("Запрос КП № 146 от 21.10.2026 – West-Med (1).pdf", null, "+77011234567", now);   // копия с «(1)», без имени чата
        l.outgoingFile("Смета.pdf", "ZZ", null, now);
        l.outgoingFile(null, "ZZ", null, now);
        assertThat(got).containsExactly("145 | ZZ Аксион", "146 | +77011234567");
    }
}
