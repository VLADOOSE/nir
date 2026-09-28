package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.dto.request.LeadItemsImportRequest;
import com.vladoose.nir.dto.response.ChatMessageResponse;
import com.vladoose.nir.dto.response.ChatResponse;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadChatTest {

    @Autowired LeadController leadController;
    @Autowired ChatController chatController;
    @Autowired ChatIngestWriter writer;
    @Autowired LeadService leadService;
    @Autowired HeaderSynonymRepository synonymRepository;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private ChatIngestWriter.Outcome write(ObjectNode body) {
        return writer.write((ParsedNotification.Message) GreenApiNotificationParser.parse(body), null, List.of());
    }

    private static LeadItemDto item(String name, String brand, int quantity) {
        LeadItemDto d = new LeadItemDto();
        d.setName(name);
        d.setBrand(brand);
        d.setQuantity(quantity);
        return d;
    }

    private static LeadItemsImportRequest request(String mode, String header, LeadItemDto... items) {
        ColumnMapping m = new ColumnMapping();
        m.setHeader(header);
        m.setField(LineField.NAME);
        LeadItemsImportRequest r = new LeadItemsImportRequest();
        r.setMode(mode);
        r.setMappings(List.of(m));
        r.setItems(List.of(items));
        return r;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void cardPointsToChatAndShowsConversationSinceLead() {
        String chat = personal();
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Реклама: у нас скидки")));
        ChatIngestWriter.Outcome first = write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Нужен облучатель")));
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Подготовим КП")));

        assertThat(leadController.get(first.createdLeadId()).getChatId()).isEqualTo(first.chatId());
        assertThat(leadController.chatMessages(first.createdLeadId()))
                .extracting(ChatMessageResponse::getBody).containsExactly("Нужен облучатель", "Подготовим КП");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void leadWithoutChatHasNoMessages() {
        LeadCreateRequest req = new LeadCreateRequest();
        req.setChannel(LeadChannel.PHONE);
        req.setContactName("Звонок");
        req.setContactPhone("+77010000000");
        Long leadId = leadController.create(req).getId();

        assertThat(leadController.get(leadId).getChatId()).isNull();
        assertThat(leadController.chatMessages(leadId)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void manualLeadFromChatShowsConversationFromItsStart() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Добрый день, это West-Med")));
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Подскажите, что ищете?")));

        ChatResponse r = chatController.createLead(o.chatId());

        assertThat(leadController.chatMessages(r.getLead().id())).extracting(ChatMessageResponse::getBody)
                .containsExactly("Добрый день, это West-Med", "Подскажите, что ищете?");
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void excelLinesReplaceOrAppendItemsAndTeachHeaders() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("Список во вложении")));
        String header = "модель аппарата " + System.nanoTime();

        LeadCardResponse replaced = leadController.importItems(o.createdLeadId(), request("REPLACE", header,
                item("Аппарат УЗИ", "Mindray", 1), item("Датчик конвексный", null, 2)));
        assertThat(replaced.getItems()).extracting(LeadItemDto::getName).containsExactly("Аппарат УЗИ", "Датчик конвексный");

        LeadCardResponse appended = leadController.importItems(o.createdLeadId(), request("APPEND", header, item("Принтер УЗИ", null, 1)));
        assertThat(appended.getItems()).extracting(LeadItemDto::getName, LeadItemDto::getQuantity)
                .containsExactly(tuple("Аппарат УЗИ", 1), tuple("Датчик конвексный", 2), tuple("Принтер УЗИ", 1));
        assertThat(appended.getEvents()).last().satisfies(e -> {
            assertThat(e.getBody()).isEqualTo("Позиции из Excel: 1 поз. (добавлены)");
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(synonymRepository.findByHeaderNorm(header)).get().extracting(HeaderSynonym::getField).isEqualTo(LineField.NAME);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closedLeadAndUnknownModeAreRejected() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("MERGE", "h", item("A", null, 1))))
                .isInstanceOf(BadRequestException.class);
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");
        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("REPLACE", "h", item("A", null, 1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotImportItems() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("REPLACE", "h", item("A", null, 1))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
