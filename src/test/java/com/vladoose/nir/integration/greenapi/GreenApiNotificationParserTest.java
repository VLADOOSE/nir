package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class GreenApiNotificationParserTest {

    static final String CLIENT = "77011234567@c.us";
    static final long T = 1_790_000_000L;

    private static ParsedNotification.Message msg(ObjectNode body) {
        ParsedNotification p = GreenApiNotificationParser.parse(body);
        assertThat(p).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingTextFromPersonalChat() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "Айгерим", "ID-1", T, GreenApiJson.text("Нужен облучатель")));

        assertThat(m.account()).isEqualTo("77000000001");
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
        assertThat(m.phone()).isEqualTo("+77011234567");
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isEqualTo("Айгерим");
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.idMessage()).isEqualTo("ID-1");
        assertThat(m.sentAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isEqualTo("Нужен облучатель");
        assertThat(m.file()).isNull();
        assertThat(m.isEdit()).isFalse();
    }

    @Test
    void contactNameFromPhoneBookWinsOverProfileName() {
        ObjectNode b = GreenApiJson.incoming(CLIENT, "Profile", "ID-2", T, GreenApiJson.text("x"));
        ((ObjectNode) b.get("senderData")).put("senderContactName", "Клиника «Шипагер»");

        assertThat(msg(b).chatName()).isEqualTo("Клиника «Шипагер»");
    }

    @Test
    void extendedAndQuotedTextsUseExtendedData() {
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-3", T,
                GreenApiJson.extended("extendedTextMessage", "см. https://westmed.kz"))).body()).isEqualTo("см. https://westmed.kz");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-4", T,
                GreenApiJson.extended("quotedMessage", "да, этот"))).body()).isEqualTo("да, этот");
    }

    @Test
    void phoneReplyIsOutgoingToRecipientChat() {
        ParsedNotification.Message m = msg(GreenApiJson.outgoing(CLIENT, "Айгерим", "ID-5", T, GreenApiJson.text("Подготовим КП")));

        assertThat(m.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isNull();
    }

    @Test
    void imageWithCaptionCarriesFileRef() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "A", "ID-6", T, GreenApiJson.file("imageMessage",
                "https://api.greenapi.com/waInstance1101/downloadFile/ABC", "photo.jpg", "image/jpeg", "Вот такой")));

        assertThat(m.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(m.body()).isEqualTo("Вот такой");
        assertThat(m.file()).isEqualTo(new FileRef("https://api.greenapi.com/waInstance1101/downloadFile/ABC", "photo.jpg", "image/jpeg"));
    }

    @Test
    void documentWithoutCaptionGetsPlaceholderText() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "A", "ID-7", T, GreenApiJson.file("documentMessage",
                "https://x/f", "Заявка.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "")));

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isNull();
        assertThat(m.displayText()).isEqualTo("[документ: Заявка.xlsx]");
    }

    @Test
    void groupMessageKeepsAuthorAndGroupTitle() {
        ParsedNotification.Message m = msg(GreenApiJson.group("120363000000000001@g.us", "Коллеги West-Med",
                "77025556677@c.us", "Данияр", "ID-8", T, GreenApiJson.text("Кто едет в Уральск?")));

        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(m.phone()).isNull();
        assertThat(m.chatName()).isEqualTo("Коллеги West-Med");
        assertThat(m.senderName()).isEqualTo("Данияр");
    }

    @Test
    void hiddenNumberChatHasNoPhone() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming("123456789012345@lid", "Скрытый", "ID-9", T, GreenApiJson.text("Добрый день")));

        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL_HIDDEN);
        assertThat(m.phone()).isNull();
    }

    @Test
    void storiesAndChannelsAreSkipped() {
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming("status@broadcast", "A", "ID-10", T, GreenApiJson.text("x"))))
                .isInstanceOf(ParsedNotification.Skip.class);
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming("120363000000000002@newsletter", "A", "ID-11", T, GreenApiJson.text("x"))))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void editDeleteAndReaction() {
        ParsedNotification.Message edit = msg(GreenApiJson.incoming(CLIENT, "A", "ID-12", T, GreenApiJson.edited("ID-1", "Нужны два облучателя")));
        assertThat(edit.isEdit()).isTrue();
        assertThat(edit.editOf()).isEqualTo("ID-1");
        assertThat(edit.body()).isEqualTo("Нужны два облучателя");

        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming(CLIENT, "A", "ID-13", T, GreenApiJson.deleted("ID-1"))))
                .isEqualTo(new ParsedNotification.Delete("77000000001", CLIENT, "ID-1"));
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming(CLIENT, "A", "ID-14", T, GreenApiJson.reaction("ID-1"))))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void stickerLocationContactAndUnknownTypes() {
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-15", T, GreenApiJson.typeOnly("stickerMessage")))).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.STICKER);
            assertThat(m.body()).isEqualTo("[стикер]");
            assertThat(m.file()).isNull();
        });
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-16", T,
                GreenApiJson.location("Клиника", "Уральск, ул. Ленина 1", 51.2, 51.37))).body())
                .isEqualTo("📍 Клиника, Уральск, ул. Ленина 1");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-17", T, GreenApiJson.contact("Иван Поставщик"))).body())
                .isEqualTo("[контакт: Иван Поставщик]");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-19", T, GreenApiJson.contacts("Иван", "Мария", "Данияр"))).body())
                .isEqualTo("[контакты: 3]");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-22", T, GreenApiJson.typeOnly("contactsArrayMessage"))).body())
                .isEqualTo("[контакты]");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-18", T, GreenApiJson.typeOnly("pollMessage")))).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.OTHER);
            assertThat(m.body()).contains("pollMessage");
        });
    }

    /**
     * Ревью 2026-09-28: отправленное ЧЕРЕЗ API (бот/другая интеграция на инстансе) — это не ответ человека с телефона;
     * по нему обращение не берётся в работу (правило §5.2 п.3 «с телефона»).
     */
    @Test
    void messageSentViaApiIsOutgoingButMarked() {
        ParsedNotification.Message api = msg(GreenApiJson.outgoingApi(CLIENT, "Айгерим", "ID-20", T, GreenApiJson.text("Спасибо за обращение!")));
        ParsedNotification.Message phone = msg(GreenApiJson.outgoing(CLIENT, "Айгерим", "ID-21", T, GreenApiJson.text("Добрый день!")));

        assertThat(api.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(api.viaApi()).isTrue();
        assertThat(phone.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(phone.viaApi()).isFalse();
    }

    @Test
    void stateQuotaAndUnknownWebhooks() {
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.state("blocked"))).isEqualTo(new ParsedNotification.State("blocked"));
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.quota())).isInstanceOf(ParsedNotification.QuotaExceeded.class);
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.webhook("outgoingMessageStatus")))
                .isInstanceOf(ParsedNotification.Skip.class);
    }
}
