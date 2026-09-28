package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class WahaEventParserTest {

    static final String CLIENT = "77011234567@c.us";
    static final String GROUP = "120363000000000001@g.us";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final long T = 1_790_000_000L;

    private static ParsedNotification.Message msg(ObjectNode env) {
        ParsedNotification p = WahaEventParser.parse(env, null);
        assertThat(p).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingTextFromPersonalChat() {
        ParsedNotification.Message m = msg(WahaJson.incomingText(CLIENT, "Айгерим", "3EB0A1", T, "Нужен облучатель"));

        assertThat(m.account()).isEqualTo("77000000001");
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
        assertThat(m.phone()).isEqualTo("+77011234567");
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isEqualTo("Айгерим");
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.idMessage()).isEqualTo("3EB0A1");      // сырой id WhatsApp — тот же, что idMessage у Green-API
        assertThat(m.sentAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isEqualTo("Нужен облучатель");
        assertThat(m.file()).isNull();
        assertThat(m.isEdit()).isFalse();
        assertThat(m.viaApi()).isFalse();
    }

    @Test
    void phoneReplyIsOutgoingAndApiSentIsMarked() {
        ParsedNotification.Message phone = msg(WahaJson.phoneReply(CLIENT, "3EB0A2", T, "Подготовим КП"));
        ParsedNotification.Message api = msg(WahaJson.apiSent(CLIENT, "3EB0A3", T, "Спасибо за обращение!"));

        assertThat(phone.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(phone.chatId()).isEqualTo(CLIENT);
        assertThat(phone.chatName()).isNull();
        assertThat(phone.senderName()).isNull();
        assertThat(phone.viaApi()).isFalse();
        assertThat(api.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(api.viaApi()).isTrue();
    }

    /** Чат — из id сообщения (поле from у своих сообщений ненадёжно, issue #2241); внутренний вид GOWS → @c.us. */
    @Test
    void chatComesFromMessageIdAndServerJidIsNormalized() {
        ObjectNode env = WahaJson.phoneReply("77011234567@s.whatsapp.net", "3EB0A4", T, "x");
        ((ObjectNode) env.get("payload")).put("from", "77099999999@c.us");

        ParsedNotification.Message m = msg(env);

        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
    }

    @Test
    void fileWithoutDownloadCarriesMessageIdNameAndSize() {
        ParsedNotification.Message m = msg(WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A5", T, "documentMessage",
                "Заявка.xlsx", XLSX, 48_213L, "Полный список"));

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isEqualTo("Полный список");
        assertThat(m.file()).isEqualTo(new FileRef(WahaJson.messageId(false, CLIENT, "3EB0A5"), "Заявка.xlsx", XLSX, 48_213L));
    }

    /** media: null — имя и тип берём из содержимого GOWS. */
    @Test
    void mediaNullStillGivesFileFromContent() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A6", T, "imageMessage", null, "image/jpeg", 120_000L, null);
        ((ObjectNode) env.get("payload")).putNull("media");

        ParsedNotification.Message m = msg(env);

        assertThat(m.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(m.displayText()).isEqualTo("[фото]");
        assertThat(m.file().mimeType()).isEqualTo("image/jpeg");
        assertThat(m.file().sizeBytes()).isEqualTo(120_000L);
    }

    /** uint64 бывает в JSON строкой — читаем обе формы. */
    @Test
    void fileLengthAsStringIsRead() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A7", T, "videoMessage", "clip.mp4", "video/mp4", null, null);
        ((ObjectNode) WahaJson.content((ObjectNode) env.get("payload")).get("videoMessage")).put("fileLength", "41943040");

        assertThat(msg(env).file().sizeBytes()).isEqualTo(41_943_040L);
    }

    @Test
    void groupMessageKeepsAuthor() {
        ParsedNotification.Message m = msg(WahaJson.groupText(GROUP, "77025556677@c.us", "Данияр", "3EB0A8", T, "Кто едет в Уральск?"));

        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(m.chatId()).isEqualTo(GROUP);
        assertThat(m.phone()).isNull();
        assertThat(m.chatName()).isNull();          // тему группы подставит источник (справочник WAHA)
        assertThat(m.senderName()).isEqualTo("Данияр");
        assertThat(m.idMessage()).isEqualTo("3EB0A8");
    }

    @Test
    void hiddenNumberGetsPhoneFromAltJid() {
        ObjectNode in = WahaJson.incomingText("123456789012345@lid", "Скрытый", "3EB0A9", T, "Добрый день");
        WahaJson.info(in).put("SenderAlt", "77012223344@s.whatsapp.net");
        ObjectNode out = WahaJson.phoneReply("123456789012345@lid", "3EB0AA", T, "Здравствуйте");
        WahaJson.info(out).put("RecipientAlt", "77012223344:12@s.whatsapp.net");
        ObjectNode unknown = WahaJson.incomingText("123456789012345@lid", "Скрытый", "3EB0AB", T, "ещё");

        assertThat(msg(in).kind()).isEqualTo(ChatKind.PERSONAL_HIDDEN);
        assertThat(msg(in).phone()).isEqualTo("+77012223344");
        assertThat(msg(out).phone()).isEqualTo("+77012223344");
        assertThat(msg(unknown).phone()).isNull();
    }

    @Test
    void editPointsToOriginalRawIdInEitherForm() {
        ParsedNotification.Message raw = msg(WahaJson.edited(CLIENT, false, "3EB0B1", "3EB0A1", T, "Нужны два облучателя"));
        ParsedNotification.Message full = msg(WahaJson.edited(CLIENT, false, "3EB0B2",
                WahaJson.messageId(false, CLIENT, "3EB0A1"), T, "Нужны три"));

        assertThat(raw.isEdit()).isTrue();
        assertThat(raw.editOf()).isEqualTo("3EB0A1");
        assertThat(raw.body()).isEqualTo("Нужны два облучателя");
        assertThat(full.editOf()).isEqualTo("3EB0A1");
    }

    @Test
    void revokeBecomesDelete() {
        assertThat(WahaEventParser.parse(WahaJson.revoked(CLIENT, false, "3EB0C1", "3EB0A1"), null))
                .isEqualTo(new ParsedNotification.Delete("77000000001", CLIENT, "3EB0A1"));
    }

    @Test
    void sessionStatusBecomesState() {
        assertThat(WahaEventParser.parse(WahaJson.sessionStatus("SCAN_QR_CODE"), null))
                .isEqualTo(new ParsedNotification.State("SCAN_QR_CODE"));
    }

    @Test
    void callsBecomeCallLinesAndOutcomesPointToThem() {
        ParsedNotification.Message received = msg(WahaJson.call("call.received", "CALL1", CLIENT, T, false, null));
        ParsedNotification.Message video = msg(WahaJson.call("call.received", "CALL2", CLIENT, T, true, null));
        ParsedNotification.Message accepted = msg(WahaJson.call("call.accepted", "CALL1", CLIENT, T + 5, false, null));
        ParsedNotification.Message rejected = msg(WahaJson.call("call.rejected", "CALL2", CLIENT, T + 5, true, null));

        assertThat(received.type()).isEqualTo(ChatMessageType.CALL);
        assertThat(received.idMessage()).isEqualTo("call:CALL1");
        assertThat(received.direction()).isEqualTo(LeadDirection.IN);
        assertThat(received.body()).isEqualTo("📞 Входящий звонок");
        assertThat(received.isEdit()).isFalse();
        assertThat(received.phone()).isEqualTo("+77011234567");
        assertThat(video.body()).isEqualTo("📹 Входящий видеозвонок");
        assertThat(accepted.editOf()).isEqualTo("call:CALL1");
        assertThat(accepted.body()).isEqualTo("📞 Входящий звонок — принят");
        assertThat(rejected.body()).isEqualTo("📹 Входящий видеозвонок — отклонён");
    }

    @Test
    void groupCallGoesToGroupChat() {
        ParsedNotification.Message m = msg(WahaJson.call("call.received", "CALL3", "77025556677@c.us", T, false, GROUP));

        assertThat(m.chatId()).isEqualTo(GROUP);
        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
    }

    @Test
    void storiesChannelsServiceContentAndUnknownEventsAreSkipped() {
        assertThat(WahaEventParser.parse(WahaJson.incomingText("status@broadcast", "A", "3EB0D1", T, "x"), null))
                .isInstanceOf(ParsedNotification.Skip.class);
        assertThat(WahaEventParser.parse(WahaJson.incomingText("120363000000000002@newsletter", "A", "3EB0D2", T, "x"), null))
                .isInstanceOf(ParsedNotification.Skip.class);
        ObjectNode reaction = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0D3", T, "👍"), "reactionMessage",
                WahaJson.M.createObjectNode().put("text", "👍"));
        assertThat(WahaEventParser.parse(reaction, null)).isInstanceOf(ParsedNotification.Skip.class);
        // правка, продублированная в message.any, не должна стать вторым пузырём — у неё своё событие message.edited
        ObjectNode editInAny = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0D4", T, "новый"), "protocolMessage",
                WahaJson.M.createObjectNode().put("type", "MESSAGE_EDIT"));
        assertThat(WahaEventParser.parse(editInAny, null)).isInstanceOf(ParsedNotification.Skip.class);
        assertThat(WahaEventParser.parse(WahaJson.envelope("presence.update", WahaJson.M.createObjectNode()), null))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void stickerLocationContactsAndUnknownContent() {
        ObjectNode sticker = WahaJson.withContent(WahaJson.incomingFile(CLIENT, "A", "3EB0E1", T, "stickerMessage", null,
                "image/webp", 9_000L, null), "stickerMessage", WahaJson.M.createObjectNode().put("mimetype", "image/webp"));
        assertThat(msg(sticker)).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.STICKER);
            assertThat(m.body()).isEqualTo("[стикер]");
            assertThat(m.file()).isNull();
        });
        ObjectNode location = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E2", T, ""), "locationMessage",
                WahaJson.M.createObjectNode().put("degreesLatitude", 51.2).put("degreesLongitude", 51.37)
                        .put("name", "Клиника").put("address", "Уральск, ул. Ленина 1"));
        assertThat(msg(location).body()).isEqualTo("📍 Клиника, Уральск, ул. Ленина 1");
        ObjectNode contact = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E3", T, ""), "contactMessage",
                WahaJson.M.createObjectNode().put("displayName", "Иван Поставщик"));
        assertThat(msg(contact).body()).isEqualTo("[контакт: Иван Поставщик]");
        ObjectNode contacts = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E4", T, ""), "contactsArrayMessage",
                WahaJson.M.createObjectNode().set("contacts", WahaJson.M.createArrayNode().add("a").add("b").add("c")));
        assertThat(msg(contacts).body()).isEqualTo("[контакты: 3]");
        ObjectNode poll = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E5", T, ""), "pollCreationMessageV3",
                WahaJson.M.createObjectNode().put("name", "Опрос"));
        assertThat(msg(poll)).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.OTHER);
            assertThat(m.body()).contains("pollCreationMessageV3");
        });
    }

    /** Нет содержимого GOWS (другой движок или урезанное событие) — по нормализованным полям WAHA. */
    @Test
    void withoutGowsContentUsesNormalizedFields() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "A", "3EB0F1", T, "documentMessage", "ТЗ.pdf", "application/pdf", 10L, "ТЗ");
        ((ObjectNode) env.get("payload")).remove("_data");

        ParsedNotification.Message m = msg(env);

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isEqualTo("ТЗ");
        assertThat(m.file()).isEqualTo(new FileRef(WahaJson.messageId(false, CLIENT, "3EB0F1"), "ТЗ.pdf", "application/pdf", null));
        assertThat(m.chatName()).isNull();          // без _data нет и PushName
    }

    /** Номер — из конверта (me) или известной сессии; наугад — никогда: сообщение ждёт следующей попытки. */
    @Test
    void accountFromEnvelopeOrKnownSessionNeverGuessed() {
        ObjectNode noMe = WahaJson.incomingText(CLIENT, "A", "3EB0G1", T, "x");
        noMe.remove("me");

        assertThat(((ParsedNotification.Message) WahaEventParser.parse(noMe, "77000000009@c.us")).account()).isEqualTo("77000000009");
        assertThatThrownBy(() -> WahaEventParser.parse(noMe, null))
                .isInstanceOf(GatewayException.class).hasMessageContaining("номер");
    }

    @Test
    void queueKeyAndEventTime() {
        ObjectNode in = WahaJson.incomingText(CLIENT, "A", "3EB0H1", T, "x");
        ObjectNode status = WahaJson.sessionStatus("WORKING");

        assertThat(WahaEventParser.messageKey(in)).isEqualTo("false_3EB0H1");
        assertThat(WahaEventParser.messageKey(WahaJson.phoneReply(CLIENT, "3EB0H1", T, "x"))).isEqualTo("true_3EB0H1");
        assertThat(WahaEventParser.messageKey(WahaJson.groupText(GROUP, "77025556677@c.us", "Д", "3EB0H2", T, "x")))
                .isEqualTo("false_3EB0H2");
        assertThat(WahaEventParser.messageKey(status)).isNull();
        assertThat(WahaEventParser.eventAt(in)).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(WahaEventParser.eventAt(status)).isEqualTo(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(status.get("timestamp").asLong()), ZoneOffset.UTC));
    }
}
