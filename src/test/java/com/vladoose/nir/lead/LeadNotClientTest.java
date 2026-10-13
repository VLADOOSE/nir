package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.request.LeadCloseRequest;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadNotClientTest {

    @Autowired LeadController controller;
    @Autowired EmailChatWriter writer;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@supplier.cn"; }

    private EmailChatWriter.Outcome letterFrom(String from, String subject) {
        clock = clock.plusMinutes(5);
        return writer.writeIncoming("info@westmed.kz", new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject,
                "Текст", clock, from, "Поставщик", List.of()));
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void notClient_closesLead_marksChat_nextLettersStartNoLead() {
        String from = address();
        EmailChatWriter.Outcome first = letterFrom(from, "Предложение о сотрудничестве");

        LeadCardResponse card = controller.notClient(first.createdLeadId());

        assertThat(card.getStatus()).isEqualTo("CLOSED");
        assertThat(card.getCloseReason()).isEqualTo("NOT_CLIENT");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getBody()).contains("не клиент").contains(from);
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isTrue();

        EmailChatWriter.Outcome next = letterFrom(from, "Повторное предложение");
        assertThat(next.createdLeadId()).isNull();
        assertThat(leadRepository.findByChatIdIn(List.of(first.chatId()))).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void leadWithoutEmailChat_400_nothingChanges() {
        Lead phone = intake.ingest(new IncomingLead(LeadSources.MANUAL, null, LeadChannel.PHONE, "Звонок",
                OffsetDateTime.now(), "Иван", "+77011234567", null, null, "Нужен аппарат", List.of(),
                LeadStatus.IN_WORK, null, "admin")).orElseThrow();

        assertThatThrownBy(() -> controller.notClient(phone.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("из почты");
        assertThat(leadRepository.findById(phone.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closedLead_400_chatNotMarked() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(BadRequestException.class);
        assertThat(chatRepository.findById(o.chatId()).orElseThrow().isNotClient()).isFalse();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_forbidden() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarket_404() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        MarketContext.set(Market.RF);

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closeWithNotClientReason_400() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        LeadCloseRequest req = new LeadCloseRequest();
        req.setReason(LeadCloseReason.NOT_CLIENT);

        assertThatThrownBy(() -> controller.close(o.createdLeadId(), req)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cardShowsChatChannel() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThat(controller.get(o.createdLeadId()).getChatChannel()).isEqualTo("EMAIL");
    }
}
