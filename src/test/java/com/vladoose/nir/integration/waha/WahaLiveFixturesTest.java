package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * События, снятые с живой WAHA 2026.9.1 (GOWS) на проде 2026-10-14 и очищенные от персональных данных (задача 13 плана
 * WAHA). Закрепляет то, чего нет в документации WAHA: где у GOWS лежат имя, телефон за @lid, размер файла, данные
 * звонка. Номера в фикстурах: свой — 77000000010 (@c.us) и 77000000011 (@lid); клиент — 77000000012@lid за телефоном
 * 77000000013; звонящий — 77000000014@lid за телефоном 77000000015; группа — 77000000016@g.us.
 */
class WahaLiveFixturesTest {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = WahaLiveFixturesTest.class.getResourceAsStream("/waha/" + name)) {
            assertThat(in).as("фикстура " + name).isNotNull();
            return M.readTree(in);
        }
    }

    static ParsedNotification parsed(String name) throws IOException {
        return WahaEventParser.parse(fixture(name), null);
    }

    static ParsedNotification.Message msg(String name) throws IOException {
        ParsedNotification p = parsed(name);
        assertThat(p).as(name).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingTextFromHiddenNumber() throws IOException {
        ParsedNotification.Message m = msg("message.any.conversation.json");
        assertThat(m.account()).isEqualTo("77000000010");
        assertThat(m.chatId()).isEqualTo("77000000012@lid");
        assertThat(m.kind()).as("@lid — скрытый номер").isEqualTo(ChatKind.PERSONAL_HIDDEN);
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isEqualTo("тест 1");
        assertThat(m.phone()).as("телефон за @lid — из _data.Info.SenderAlt").isEqualTo("+77000000013");
        assertThat(m.chatName()).as("PushName из _data.Info").isEqualTo("Клиент 2");
        assertThat(m.viaApi()).isFalse();
    }

    /** Ответ с телефона ложится в чат собеседника, не в свой (issue #2241 WAHA); телефон — из RecipientAlt. */
    @Test
    void phoneReplyIsOutgoingInTheRecipientsChat() throws IOException {
        ParsedNotification.Message m = msg("message.any.conversation.fromMe.json");
        assertThat(m.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(m.viaApi()).isFalse();
        assertThat(m.chatId()).isEqualTo("77000000012@lid");
        assertThat(m.phone()).isEqualTo("+77000000013");
        assertThat(m.body()).isEqualTo("ответ 1");
    }

    /** Главное условие включения на проде: размер файла есть в самом событии — АИС решает «не качать» без скачивания. */
    @Test
    void documentCarriesSizeNameAndType() throws IOException {
        ParsedNotification.Message m = msg("message.any.documentMessage.json");
        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.file()).isNotNull();
        assertThat(m.file().sizeBytes()).as("documentMessage.fileLength").isEqualTo(189003L);
        assertThat(m.file().fileName()).isEqualTo("документ.pdf");
        assertThat(m.file().mimeType()).isEqualTo("application/pdf");
    }

    @Test
    void largeVideoSentAsDocumentIsKnownToBeLargeBeforeDownload() throws IOException {
        ParsedNotification.Message m = msg("message.any.documentMessage.large.json");
        assertThat(m.file().sizeBytes()).as("41 МБ — больше предела 25 МБ").isEqualTo(43881969L);
        assertThat(m.file().mimeType()).isEqualTo("video/quicktime");
    }

    @Test
    void imageVideoAndStickerCarrySize() throws IOException {
        ParsedNotification.Message image = msg("message.any.imageMessage.json");
        assertThat(image.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(image.file().sizeBytes()).isEqualTo(320577L);
        ParsedNotification.Message video = msg("message.any.videoMessage.json");
        assertThat(video.type()).isEqualTo(ChatMessageType.VIDEO);
        assertThat(video.file().sizeBytes()).isEqualTo(563612L);
        assertThat(msg("message.any.stickerMessage.json").type()).isEqualTo(ChatMessageType.STICKER);
    }

    @Test
    void locationAndContact() throws IOException {
        ParsedNotification.Message location = msg("message.any.locationMessage.json");
        assertThat(location.type()).isEqualTo(ChatMessageType.LOCATION);
        assertThat(location.body()).contains("51.2333").contains("51.3667");
        ParsedNotification.Message contact = msg("message.any.contactMessage.json");
        assertThat(contact.type()).isEqualTo(ChatMessageType.CONTACT);
        assertThat(contact.body()).startsWith("[контакт");
    }

    @Test
    void editPointsToTheOriginal() throws IOException {
        ParsedNotification.Message m = msg("message.edited.json");
        assertThat(m.isEdit()).isTrue();
        assertThat(m.editOf()).isEqualTo("3A1F1039DB12DACECB09");
        assertThat(m.chatId()).isEqualTo("77000000012@lid");
        assertThat(m.body()).isEqualTo("тест 10101");
    }

    @Test
    void revokeDeletesByIdInTheSameChat() throws IOException {
        assertThat(parsed("message.revoked.json"))
                .isEqualTo(new ParsedNotification.Delete("77000000010", "77000000012@lid", "3A29B6D30974D2784AF6"));
    }

    @Test
    void sessionStatus() throws IOException {
        assertThat(parsed("session.status.json")).isInstanceOf(ParsedNotification.State.class);
    }

    @Test
    void groupMessageIsGroupWithSenderName() throws IOException {
        ParsedNotification.Message m = msg("message.any.conversation.group.json");
        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(m.chatId()).isEqualTo("77000000016@g.us");
        assertThat(m.senderName()).isEqualTo("Клиент 2");
        assertThat(m.phone()).isNull();
    }

    /** Догонка собирает сообщения из истории WAHA — форма та же, размер файла тоже есть (условие включения). */
    @Test
    void catchUpMessagesParseTheSameWithSize() throws IOException {
        ParsedNotification.Message text = msg("message.any.conversation.group.catchup.json");
        assertThat(text.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(text.body()).isEqualTo("догонка 1");
        ParsedNotification.Message image = msg("message.any.imageMessage.group.catchup.json");
        assertThat(image.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(image.file().sizeBytes()).isEqualTo(185655L);
        assertThat(image.body()).isEqualTo("догонка 2");
    }

    @Test
    void receivedCallIsInTheCallersChat() throws IOException {
        ParsedNotification.Message m = msg("call.received.json");
        assertThat(m.type()).isEqualTo(ChatMessageType.CALL);
        assertThat(m.chatId()).isEqualTo("77000000014@lid");
        assertThat(m.body()).isEqualTo("📹 Входящий видеозвонок");
        assertThat(m.isEdit()).isFalse();
    }

    /**
     * Исход звонка шлёт НАШЕ устройство: from — свой номер (me.lid 77000000011), собеседник — _data.CallCreator.
     * Звонок ложился в чат своего номера и заводил обращение на самого себя (живая WAHA, 2026-10-14).
     */
    @Test
    void callOutcomesGoToTheCallersChatNotOwn() throws IOException {
        ParsedNotification.Message accepted = msg("call.accepted.json");
        assertThat(accepted.chatId()).isEqualTo("77000000014@lid");
        assertThat(accepted.body()).isEqualTo("📞 Входящий звонок — принят");
        assertThat(accepted.editOf()).isEqualTo("call:00F823E6CD6E6F5C153B81C687064959");

        ParsedNotification.Message rejected = msg("call.rejected.json");
        assertThat(rejected.chatId()).isEqualTo("77000000014@lid");
        assertThat(rejected.editOf()).as("исход — правка строки call.received")
                .isEqualTo("call:" + msg("call.received.json").idMessage().substring("call:".length()));
    }
}
