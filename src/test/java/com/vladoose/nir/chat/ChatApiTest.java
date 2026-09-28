package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.dto.request.ChatNotClientRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatApiTest {

    @Autowired ChatController controller;
    @Autowired ChatIngestWriter writer;
    @Autowired LeadRepository leadRepository;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private ChatIngestWriter.Outcome write(ObjectNode body) {
        return writer.write((ParsedNotification.Message) GreenApiNotificationParser.parse(body), null, List.of());
    }

    private ChatIngestWriter.Outcome writeFile(ObjectNode body, byte[] bytes) {
        ParsedNotification.Message m = (ParsedNotification.Message) GreenApiNotificationParser.parse(body);
        return writer.write(m, IncomingFile.stored(m.file(), bytes), List.of());
    }

    private static ChatNotClientRequest notClient(boolean v) {
        ChatNotClientRequest r = new ChatNotClientRequest();
        r.setValue(v);
        return r;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void listShowsChatsWithCurrentLeadAndFilters() {
        String client = personal();
        ChatIngestWriter.Outcome withLead = write(GreenApiJson.incoming(client, "Айгерим-" + client, id(), clock += 60,
                GreenApiJson.text("Нужен облучатель")));
        String colleague = personal();
        ChatIngestWriter.Outcome noLead = write(GreenApiJson.outgoing(colleague, "Данияр-" + colleague, id(), clock += 60,
                GreenApiJson.text("Привет")));

        ChatListItemResponse a = controller.list("ALL", null).stream()
                .filter(c -> c.getId().equals(withLead.chatId())).findFirst().orElseThrow();
        assertThat(a.getLead().status()).isEqualTo("NEW");
        assertThat(a.getLastMessagePreview()).isEqualTo("Нужен облучатель");

        assertThat(controller.list("WITH_LEAD", null)).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThat(controller.list("WITHOUT_LEAD", null)).extracting(ChatListItemResponse::getId)
                .contains(noLead.chatId()).doesNotContain(withLead.chatId());
        assertThat(controller.list("ALL", "облучатель")).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThat(controller.list("ALL", client.substring(4, 11))).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThatThrownBy(() -> controller.list("BOGUS", null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void messagesComeInPagesChronologicalWithinPage() {
        String chat = personal();
        ChatIngestWriter.Outcome o = null;
        for (int i = 1; i <= 5; i++) {
            o = write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("сообщение " + i)));
        }

        List<ChatMessageResponse> last2 = controller.messages(o.chatId(), null, 2);
        assertThat(last2).extracting(ChatMessageResponse::getBody).containsExactly("сообщение 4", "сообщение 5");
        assertThat(controller.messages(o.chatId(), last2.get(0).getId(), 2))
                .extracting(ChatMessageResponse::getBody).containsExactly("сообщение 2", "сообщение 3");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void imageIsInlineButHtmlIsDownloadOnly() {
        String chat = personal();
        ChatIngestWriter.Outcome img = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/p", "фото.png", "image/png", "")), new byte[]{1, 2});
        ChatIngestWriter.Outcome html = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", "https://x/h", "page.html", "text/html", "")),
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        List<ChatMessageResponse> msgs = controller.messages(img.chatId(), null, 10);
        ChatAttachmentResponse imgMeta = msgs.stream().filter(m -> m.getId().equals(img.messageId())).findFirst().orElseThrow().getAttachment();
        ChatAttachmentResponse htmlMeta = msgs.stream().filter(m -> m.getId().equals(html.messageId())).findFirst().orElseThrow().getAttachment();
        assertThat(imgMeta.isImage()).isTrue();
        assertThat(htmlMeta.isImage()).isFalse();
        assertThat(htmlMeta.isStored()).isTrue();

        ResponseEntity<byte[]> image = controller.attachment(img.chatId(), imgMeta.getId());
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(image.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        ResponseEntity<byte[]> page = controller.attachment(html.chatId(), htmlMeta.getId());
        assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(page.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void chatOfOtherMarketIsNotFound() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.get(o.chatId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.messages(o.chatId(), null, 10)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void adminCreatesLeadFromChatWrittenFirstByUs() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.text("Добрый день, это West-Med")));

        ChatResponse r = controller.createLead(o.chatId());

        assertThat(r.getLead().status()).isEqualTo("IN_WORK");
        assertThat(r.isLeadOpen()).isTrue();
        Lead l = leadRepository.findById(r.getLead().id()).orElseThrow();
        assertThat(l.getSource()).isEqualTo(LeadSources.WHATSAPP);
        assertThat(l.getChat().getId()).isEqualTo(o.chatId());
        assertThat(l.getReceivedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(clock));   // начало переписки
        assertThat(l.getEvents().get(0).getAuthor()).isEqualTo("manager1");
        assertThatThrownBy(() -> controller.createLead(o.chatId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void notClientSwitchStopsAutomaticLeads() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Данияр", id(), clock += 60, GreenApiJson.text("Привет")));

        assertThat(controller.notClient(o.chatId(), notClient(true)).isNotClient()).isTrue();
        ChatIngestWriter.Outcome reply = write(GreenApiJson.incoming(chat, "Данияр", id(), clock += 60, GreenApiJson.text("Привет, до завтра")));

        assertThat(reply.createdLeadId()).isNull();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(personal(), "Данияр", id(), clock += 60, GreenApiJson.text("Привет")));

        assertThat(controller.get(o.chatId()).getId()).isEqualTo(o.chatId());
        assertThat(controller.status()).isNotNull();
        assertThatThrownBy(() -> controller.createLead(o.chatId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.notClient(o.chatId(), notClient(true))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excelAttachmentIsPreviewedByTheImportGrid() throws IOException {
        byte[] xlsx = xlsx(new String[]{"Наименование", "Кол-во"}, new String[]{"Аппарат УЗИ", "1"});
        ChatIngestWriter.Outcome o = writeFile(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", "https://x/l", "заявка.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "")), xlsx);
        ChatAttachmentResponse att = controller.messages(o.chatId(), null, 1).get(0).getAttachment();
        assertThat(att.isExcel()).isTrue();

        ImportPreviewResponse p = controller.preview(o.chatId(), att.getId());

        assertThat(p.getColumns()).extracting(PreviewColumnResponse::getHeader).contains("Наименование");
        assertThat(p.getRows()).anySatisfy(row -> assertThat(row).contains("Аппарат УЗИ"));
    }

    static byte[] xlsx(String[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Заявка");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
