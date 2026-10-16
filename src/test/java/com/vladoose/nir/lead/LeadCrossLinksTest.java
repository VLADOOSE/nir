package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.controller.PrivateRequestController;
import com.vladoose.nir.dto.response.ChatLeadRef;
import com.vladoose.nir.dto.response.LeadConvertResponse;
import com.vladoose.nir.dto.response.LeadListItemResponse;
import com.vladoose.nir.dto.response.LeadRefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/**
 * Переходы между страницами (2026-10-16): фильтр «Активные» в обращениях — с превращёнными в ОТКРЫТУЮ заявку,
 * ссылка на заявку в шапке чата, ссылка на переписку в заявке.
 */
@SpringBootTest
@Transactional
class LeadCrossLinksTest {

    @Autowired LeadController leadController;
    @Autowired ChatController chatController;
    @Autowired PrivateRequestController privateRequestController;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired PrivateRequestService privateRequests;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatIngestWriter chatWriter;

    /** Метка контакта: поиск по ней отрезает обращения живой nirdb — в выдаче только обращения теста. */
    private final String tag = "ZZ-переходы-" + System.nanoTime();
    private long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Lead lead(LeadChannel channel, LeadStatus status) {
        return intake.ingest(new IncomingLead(LeadSources.MANUAL, null, channel, "Звонок", null, tag, null, null,
                null, "Нужен облучатель", List.of(), status, null, "t")).orElseThrow();
    }

    /** Обращение → частная заявка одной кнопкой; заявка закрыта исходом outcome (NEW — открыта). */
    private Lead converted(PrivateRequestStatus outcome) {
        Lead l = lead(LeadChannel.PHONE, LeadStatus.IN_WORK);
        LeadConvertResponse r = leadService.convert(l.getId(), null, "t");
        if (outcome != PrivateRequestStatus.NEW) privateRequests.close(r.getPrivateRequestId(), outcome);
        return l;
    }

    private List<Long> ids(List<LeadListItemResponse> rows) {
        return rows.stream().map(LeadListItemResponse::getId).toList();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void activeIsNewInWorkAndConvertedWithOpenRequest() {
        Lead fresh = lead(LeadChannel.PHONE, LeadStatus.NEW);
        Lead inWork = lead(LeadChannel.PHONE, LeadStatus.IN_WORK);
        Lead open = converted(PrivateRequestStatus.NEW);
        Lead won = converted(PrivateRequestStatus.WON);
        Lead lost = converted(PrivateRequestStatus.LOST);
        Lead cancelled = converted(PrivateRequestStatus.CANCELLED);
        Lead closed = lead(LeadChannel.PHONE, LeadStatus.IN_WORK);
        leadService.close(closed.getId(), LeadCloseReason.SPAM, null, "t");
        // «превращено» без заявки (заявку удалили мимо обычного пути) — показывать нечего
        Lead orphan = lead(LeadChannel.PHONE, LeadStatus.NEW);
        orphan.setStatus(LeadStatus.CONVERTED);
        leadRepository.flush();

        assertThat(ids(leadController.list("ACTIVE", null, tag)))
                .containsExactlyInAnyOrder(fresh.getId(), inWork.getId(), open.getId())
                .doesNotContain(won.getId(), lost.getId(), cancelled.getId(), closed.getId(), orphan.getId());
        // ключ — без учёта регистра и пробелов по краям, как прочие значения status
        assertThat(ids(leadController.list(" active ", null, tag)))
                .containsExactlyInAnyOrder(fresh.getId(), inWork.getId(), open.getId());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void activeRespectsChannel() {
        Lead phone = converted(PrivateRequestStatus.NEW);
        Lead whatsapp = lead(LeadChannel.WHATSAPP, LeadStatus.NEW);

        assertThat(ids(leadController.list("ACTIVE", LeadChannel.PHONE, tag))).containsExactly(phone.getId());
        assertThat(ids(leadController.list("ACTIVE", LeadChannel.WHATSAPP, tag))).containsExactly(whatsapp.getId());
        assertThat(ids(leadController.list("ACTIVE", null, tag)))
                .containsExactlyInAnyOrder(phone.getId(), whatsapp.getId());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void oldStatusValuesWorkAsBefore() {
        Lead fresh = lead(LeadChannel.PHONE, LeadStatus.NEW);
        Lead open = converted(PrivateRequestStatus.NEW);
        Lead won = converted(PrivateRequestStatus.WON);

        assertThat(ids(leadController.list("NEW,IN_WORK", null, tag))).containsExactly(fresh.getId());
        assertThat(ids(leadController.list("CONVERTED", null, tag))).containsExactlyInAnyOrder(open.getId(), won.getId());
        assertThat(ids(leadController.list("ALL", null, tag)))
                .containsExactlyInAnyOrder(fresh.getId(), open.getId(), won.getId());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void chatHeaderLinksToPrivateRequestOfItsLead() {
        String client = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        ChatIngestWriter.Outcome o = chatWriter.write((ParsedNotification.Message) GreenApiNotificationParser.parse(
                GreenApiJson.incoming(client, "Айгерим", UUID.randomUUID().toString(), clock += 60,
                        GreenApiJson.text("Нужен облучатель"))), null, List.of());

        ChatLeadRef before = chatController.get(o.chatId()).getLead();
        assertThat(before.id()).isEqualTo(o.createdLeadId());
        assertThat(before.privateRequestId()).isNull();
        assertThat(before.privateRequestNumber()).isNull();

        LeadConvertResponse r = leadService.convert(o.createdLeadId(), null, "t");

        ChatLeadRef after = chatController.get(o.chatId()).getLead();
        assertThat(after.status()).isEqualTo("CONVERTED");
        assertThat(after.privateRequestId()).isEqualTo(r.getPrivateRequestId());
        assertThat(after.privateRequestNumber()).isEqualTo(r.getNumber()).startsWith("ЧЗ-");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void privateRequestPointsToTheChatOfItsLead() {
        String client = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        ChatIngestWriter.Outcome o = chatWriter.write((ParsedNotification.Message) GreenApiNotificationParser.parse(
                GreenApiJson.incoming(client, "Айгерим", UUID.randomUUID().toString(), clock += 60,
                        GreenApiJson.text("Нужен облучатель"))), null, List.of());
        LeadConvertResponse withChat = leadService.convert(o.createdLeadId(), null, "t");
        LeadConvertResponse noChat = leadService.convert(lead(LeadChannel.PHONE, LeadStatus.IN_WORK).getId(), null, "t");

        LeadRefResponse ref = privateRequestController.get(withChat.getPrivateRequestId()).getLead();
        assertThat(ref.getId()).isEqualTo(o.createdLeadId());
        assertThat(ref.getChatId()).isEqualTo(o.chatId());
        assertThat(privateRequestController.get(noChat.getPrivateRequestId()).getLead().getChatId()).isNull();
    }
}
