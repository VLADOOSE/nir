package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.dto.request.ChatNotClientRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadCloseReason;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
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
import java.time.OffsetDateTime;
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
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;

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

    /** Гард ChatService.attachment: файл отдаётся только через СВОЙ чат своего рынка (ревью: мутацией не защищён). */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void attachmentIsServedOnlyThroughItsOwnChat() {
        ChatIngestWriter.Outcome withFile = writeFile(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/p", "фото.png", "image/png", "")), new byte[]{1, 2});
        ChatIngestWriter.Outcome other = write(GreenApiJson.incoming(personal(), "Ерлан", id(), clock += 60, GreenApiJson.text("x")));
        Long attId = controller.messages(withFile.chatId(), null, 1).get(0).getAttachment().getId();

        assertThatThrownBy(() -> controller.attachment(other.chatId(), attId)).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.attachment(withFile.chatId(), attId)).isInstanceOf(NotFoundException.class);
    }

    /** SVG — картинка со скриптом внутри: никогда не inline; кривые параметры MIME отправителя не валят отдачу 500-й. */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void svgIsDownloadOnlyAndOddImageParametersAreIgnored() {
        String chat = personal();
        ChatIngestWriter.Outcome svg = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/s", "схема.svg", "image/svg+xml", "")),
                "<svg onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8));
        ChatIngestWriter.Outcome odd = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/o", "фото.png", "image/png; name=\"фото", "")), new byte[]{1});
        List<ChatMessageResponse> msgs = controller.messages(svg.chatId(), null, 10);
        Long svgId = msgs.stream().filter(m -> m.getId().equals(svg.messageId())).findFirst().orElseThrow().getAttachment().getId();
        Long oddId = msgs.stream().filter(m -> m.getId().equals(odd.messageId())).findFirst().orElseThrow().getAttachment().getId();

        ResponseEntity<byte[]> s = controller.attachment(svg.chatId(), svgId);
        assertThat(s.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(s.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");

        ResponseEntity<byte[]> o = controller.attachment(odd.chatId(), oddId);
        assertThat(o.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(o.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
    }

    /**
     * Ревью 2026-09-28: обращение с сайта того же номера (ещё не привязанное к чату) прятало кнопку «Создать обращение»
     * (leadOpen), но в шапку не попадало — ни кнопки, ни ссылки.
     */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void openLeadFoundByPhoneIsShownInChatHeader() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Добрый день")));
        Lead site = intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:" + UUID.randomUUID(), LeadChannel.SITE,
                "Запрос цены", OffsetDateTime.now(), "Айгерим", "+" + chat.substring(0, 11), null, null, "Нужен облучатель",
                List.of(), LeadStatus.NEW, null, null)).orElseThrow();

        ChatResponse r = controller.get(o.chatId());

        assertThat(r.isLeadOpen()).isTrue();
        assertThat(r.getLead()).isNotNull();
        assertThat(r.getLead().id()).isEqualTo(site.getId());
    }

    /**
     * Ревью 2026-09-28: «Создать обращение» после ЗАКРЫТОГО обращения брало начало от начала прошлого эпизода —
     * карточка нового снова показывала старую переписку. Начало — первое сообщение после КОНЦА прошлого обращения.
     */
    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void manualLeadStartsAfterPreviousLeadEnded() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Нужен облучатель")));
        write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("и рециркулятор")));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "manager1");
        long later = Instant.now().getEpochSecond() + 120;   // после закрытия (updatedAt — реальное «сейчас»)
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), later, GreenApiJson.text("Добрый день! Есть новое предложение")));

        ChatResponse r = controller.createLead(first.chatId());

        Lead l = leadRepository.findById(r.getLead().id()).orElseThrow();
        assertThat(l.getReceivedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(later));
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
