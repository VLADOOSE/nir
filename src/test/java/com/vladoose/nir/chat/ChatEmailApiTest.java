package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.service.mail.TestMimes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatEmailApiTest {

    static final String BOX = "info@westmed.kz";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired ChatController controller;
    @Autowired EmailChatWriter writer;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadService leadService;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /**
     * Метка без цифр: в поиске «Чатов» цифры запроса (от 4) ищутся и в номерах телефонов — адрес или тема с цифрами
     * зацепили бы чаты WhatsApp базы, и проверка «только эта переписка» зависела бы от чужих данных.
     */
    static String letters(int n) {
        StringBuilder s = new StringBuilder();
        for (char ch : UUID.randomUUID().toString().replace("-", "").substring(0, n).toCharArray()) {
            s.append(Character.isDigit(ch) ? (char) ('g' + (ch - '0')) : ch);
        }
        return s.toString();
    }

    static String address() { return "zz-" + letters(8) + "@clinic.kz"; }

    private EmailChatWriter.Outcome in(String from, String subject, String body, MailFile... files) {
        clock = clock.plusMinutes(5);
        return writer.writeIncoming(BOX, new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject, body, clock,
                from, "Айгуль", List.of(files)));
    }

    @Test
    void emailFilter_searchByAddressAndSubject_listItemHasAddress() {
        String from = address();
        String tag = letters(6);
        EmailChatWriter.Outcome o = in(from, "Кушетки " + tag, "Текст");

        List<ChatListItemResponse> email = controller.list("EMAIL", null);
        assertThat(email).extracting(ChatListItemResponse::getId).contains(o.chatId());
        assertThat(email).allSatisfy(c -> assertThat(c.getChannel()).isEqualTo("EMAIL"));
        assertThat(controller.list("ALL", from)).extracting(ChatListItemResponse::getId).containsExactly(o.chatId());
        assertThat(controller.list("ALL", "кушетки " + tag)).extracting(ChatListItemResponse::getId).containsExactly(o.chatId());
        ChatListItemResponse item = email.stream().filter(c -> c.getId().equals(o.chatId())).findFirst().orElseThrow();
        assertThat(item.getEmail()).isEqualTo(from);
        assertThat(item.getPhone()).isNull();
        assertThat(controller.get(o.chatId()).getEmail()).isEqualTo(from);
    }

    @Test
    void letterHasSubjectAndAllFilesInOrder_firstIsAlsoAttachment() {
        EmailChatWriter.Outcome o = in(address(), "Смета", "Во вложении",
                new MailFile("смета.xlsx", XLSX, 3L, new byte[]{1, 2, 3}),
                new MailFile("фото.png", "image/png", 4L, new byte[]{1, 2, 3, 4}),
                new MailFile("видео.mp4", "video/mp4", null, null));

        ChatMessageResponse m = controller.messages(o.chatId(), null, 10).get(0);

        assertThat(m.getType()).isEqualTo("EMAIL");
        assertThat(m.getSubject()).isEqualTo("Смета");
        assertThat(m.getAttachments()).extracting(ChatAttachmentResponse::getFileName)
                .containsExactly("смета.xlsx", "фото.png", "видео.mp4");
        assertThat(m.getAttachments().get(0).isExcel()).isTrue();
        assertThat(m.getAttachments().get(1).isImage()).isTrue();
        assertThat(m.getAttachments().get(2).isStored()).isFalse();
        assertThat(m.getAttachment().getFileName()).isEqualTo("смета.xlsx");
    }

    @Test
    void emailFile_servedThroughItsChat_notStoredSaysOpenInMail_otherMarket404() {
        EmailChatWriter.Outcome o = in(address(), "ТЗ", "Во вложении",
                new MailFile("ТЗ.pdf", "application/pdf", 2L, new byte[]{4, 5}),
                new MailFile("видео.mp4", "video/mp4", null, null));
        List<ChatAttachmentResponse> files = controller.messages(o.chatId(), null, 10).get(0).getAttachments();

        ResponseEntity<byte[]> pdf = controller.attachment(o.chatId(), files.get(0).getId());
        assertThat(pdf.getBody()).isEqualTo(new byte[]{4, 5});
        assertThatThrownBy(() -> controller.attachment(o.chatId(), files.get(1).getId()))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("откройте его в почте");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.attachment(o.chatId(), files.get(0).getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excelFromLetter_parsesIntoPreview() throws Exception {
        EmailChatWriter.Outcome o = in(address(), "Смета", "Во вложении", new MailFile("смета.xlsx", XLSX, 1L, TestMimes.xlsxBytes()));
        Long excelId = controller.messages(o.chatId(), null, 10).get(0).getAttachments().get(0).getId();

        ImportPreviewResponse p = controller.preview(o.chatId(), excelId);

        assertThat(p.getRows()).anySatisfy(row -> assertThat(row).contains("Аппарат УЗИ"));
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void createLeadOnEmailChat_isEmailLeadInWork() {
        String from = address();
        EmailChatWriter.Outcome o = in(from, "Запрос цены", "Нужны кушетки");
        leadService.close(o.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        ChatResponse r = controller.createLead(o.chatId());

        assertThat(r.isLeadOpen()).isTrue();
        Lead l = leadRepository.findById(r.getLead().id()).orElseThrow();
        assertThat(l.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getSubject()).isEqualTo("Запрос цены");
        assertThat(l.getContactEmail()).isEqualTo(from);
        assertThat(l.getContactName()).isEqualTo("Айгуль");
        assertThat(l.getPhoneNorm()).isNull();
    }
}
