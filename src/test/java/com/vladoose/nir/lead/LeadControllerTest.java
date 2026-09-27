package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.dto.response.LeadListItemResponse;
import com.vladoose.nir.dto.response.LeadRefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.service.LeadIntakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadControllerTest {

    @Autowired LeadController controller;
    @Autowired LeadIntakeService intake;

    @AfterEach void clear() { MarketContext.clear(); }

    private Lead lead(String phone) {
        MarketContext.set(Market.KZ);
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", phone, "a-" + System.nanoTime() + "@zz.kz", "ТОО «ZZ»",
                "Нужны облучатели", List.of(
                        new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, null),
                        new IncomingLead.Item("Облучатель ОБН-75", "Азов", 1, null),
                        new IncomingLead.Item("Рециркулятор", null, 1, null)),
                LeadStatus.NEW, "NEW", null)).orElseThrow();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() {
        Lead l = lead(null);
        assertThat(controller.get(l.getId()).getId()).isEqualTo(l.getId());
        assertThatThrownBy(() -> controller.take(l.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void actionIsAuthoredByCurrentUser() {
        Lead l = lead(null);

        LeadCardResponse card = controller.take(l.getId());

        assertThat(card.getStatus()).isEqualTo("IN_WORK");
        assertThat(card.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listItemPreviewsFirstTwoPositions() {
        Lead l = lead(null);

        LeadListItemResponse item = controller.list("NEW", null, "ОБН-150").stream()
                .filter(x -> x.getId().equals(l.getId())).findFirst().orElseThrow();

        assertThat(item.getItemsCount()).isEqualTo(3);
        assertThat(item.getItemsPreview()).containsExactly("Облучатель ОБН-150", "Облучатель ОБН-75");
        assertThat(item.getChannel()).isEqualTo("SITE");
        assertThat(item.getSource()).isEqualTo("westmed.kz");
        assertThat(item.getMessagePreview()).isEqualTo("Нужны облучатели");
        assertThat(item.isSyncError()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cardListsOtherLeadsFromTheSamePhone() {
        String phone = "+7799" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        Lead first = lead(phone);
        Lead second = lead(phone);

        assertThat(controller.get(second.getId()).getSamePhone())
                .extracting(LeadRefResponse::getId).containsExactly(first.getId());
    }

    @Test
    void parseStatusesUnderstandsListsAndAll() {
        assertThat(LeadController.parseStatuses("NEW,IN_WORK")).containsExactlyInAnyOrder(LeadStatus.NEW, LeadStatus.IN_WORK);
        assertThat(LeadController.parseStatuses("ALL")).containsExactlyInAnyOrder(LeadStatus.values());
        assertThatThrownBy(() -> LeadController.parseStatuses("NEW,BOGUS")).isInstanceOf(BadRequestException.class);
    }
}
