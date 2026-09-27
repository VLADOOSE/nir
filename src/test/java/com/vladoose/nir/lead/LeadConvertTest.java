package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.PrivateRequestController;
import com.vladoose.nir.dto.request.LeadConvertRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.LeadConvertResponse;
import com.vladoose.nir.dto.response.PrivateRequestResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadConvertTest {

    @Autowired LeadService service;
    @Autowired LeadIntakeService intake;
    @Autowired FacilityRepository facilityRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired PrivateRequestController privateRequestController;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Lead siteLead() {
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим Сапарова", "+7 777 000 00 03", "aigerim-" + System.nanoTime() + "@zz.kz",
                "ТОО «ZZ Клиника»", "Нужны облучатели",
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, null)),
                LeadStatus.NEW, "NEW", null)).orElseThrow();
    }

    private Facility client(Market market) {
        return facilityRepository.save(Facility.builder()
                .name("ZZ Конверт клиент " + System.nanoTime()).market(market).build());
    }

    private static PrivateRequestCreate.Line line(String name, String brand, int qty) {
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName(name);
        l.setManufact(brand);
        l.setQuantity(qty);
        return l;
    }

    private static LeadConvertRequest withClient(Long facilityId) {
        LeadConvertRequest r = new LeadConvertRequest();
        r.setClientFacilityId(facilityId);
        r.setNote("Нужны облучатели");
        r.setLines(List.of(line("Облучатель ОБН-150", "Азов", 2)));
        return r;
    }

    private static LeadConvertRequest withNewClient(String name) {
        LeadConvertRequest.NewClient nc = new LeadConvertRequest.NewClient();
        nc.setName(name);
        nc.setPhone("+7 777 000 00 03");
        nc.setFirstName("Айгерим");
        nc.setLastName("Сапарова");
        LeadConvertRequest r = new LeadConvertRequest();
        r.setNewClient(nc);
        r.setLines(List.of(line("Облучатель ОБН-150", "Азов", 2)));
        return r;
    }

    @Test
    void existingClientGetsPrivateRequestAndLeadIsLinked() {
        Lead l = siteLead();
        Facility f = client(Market.KZ);

        LeadConvertResponse r = service.convert(l.getId(), withClient(f.getId()), "admin");

        Tender t = tenderRepository.findById(r.getPrivateRequestId()).orElseThrow();
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getTenderNumber()).isEqualTo(r.getNumber()).startsWith("ЧЗ-");
        assertThat(t.getFacility().getId()).isEqualTo(f.getId());
        assertThat(t.getDescription()).isEqualTo("Нужны облучатели");
        assertThat(t.getContactPhone()).isEqualTo("+7 777 000 00 03");
        assertThat(t.getLots()).extracting(TenderLot::getEquipName, TenderLot::getManufact, TenderLot::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2));

        Lead back = service.get(l.getId());
        assertThat(back.getStatus()).isEqualTo(LeadStatus.CONVERTED);
        assertThat(back.getPrivateRequest().getId()).isEqualTo(t.getId());
        assertThat(back.getFacility().getId()).isEqualTo(f.getId());
        assertThat(back.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(back.getEvents().get(back.getEvents().size() - 1).getBody()).contains(r.getNumber());
    }

    @Test
    void newClientIsCreatedInCurrentMarket() {
        Lead l = siteLead();
        String name = "ZZ Новый клиент " + System.nanoTime();

        service.convert(l.getId(), withNewClient(name), "admin");

        Facility f = service.get(l.getId()).getFacility();
        assertThat(f.getName()).isEqualTo(name);
        assertThat(f.getMarket()).isEqualTo(Market.KZ);
        assertThat(f.getLastName()).isEqualTo("Сапарова");
        assertThat(f.getPhone()).isEqualTo("+7 777 000 00 03");
    }

    @Test
    void takenClientNameIsRejectedAndNothingIsCreated() {
        Lead l = siteLead();
        Facility existing = client(Market.KZ);
        int before = tenderRepository.findBySource(Source.PRIVATE_REQUEST).size();

        assertThatThrownBy(() -> service.convert(l.getId(), withNewClient(existing.getName()), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже есть");

        assertThat(tenderRepository.findBySource(Source.PRIVATE_REQUEST)).hasSize(before);
        assertThat(service.get(l.getId()).getStatus()).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void secondConversionIsRejected() {
        Lead l = siteLead();
        Facility f = client(Market.KZ);
        service.convert(l.getId(), withClient(f.getId()), "admin");

        assertThatThrownBy(() -> service.convert(l.getId(), withClient(f.getId()), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void linesAreRequired() {
        Lead l = siteLead();
        LeadConvertRequest r = withClient(client(Market.KZ).getId());
        r.setLines(List.of(line("  ", null, 1)));

        assertThatThrownBy(() -> service.convert(l.getId(), r, "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("строка");
    }

    @Test
    void exactlyOneClientChoiceIsRequired() {
        Lead l = siteLead();
        LeadConvertRequest none = withClient(null);
        LeadConvertRequest both = withNewClient("ZZ Оба " + System.nanoTime());
        both.setClientFacilityId(client(Market.KZ).getId());

        assertThatThrownBy(() -> service.convert(l.getId(), none, "admin")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.convert(l.getId(), both, "admin")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void clientOfAnotherMarketIsNotFound() {
        Lead l = siteLead();
        Facility rf = client(Market.RF);

        assertThatThrownBy(() -> service.convert(l.getId(), withClient(rf.getId()), "admin"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reopenAfterConvertAndCloseReturnsToConverted() {
        Lead l = siteLead();
        service.convert(l.getId(), withClient(client(Market.KZ).getId()), "admin");
        service.close(l.getId(), LeadCloseReason.CLIENT_DECLINED, null, "admin");

        assertThat(service.reopen(l.getId(), "admin").getStatus()).isEqualTo(LeadStatus.CONVERTED);
    }

    @Test
    void privateRequestCardLinksBackToLead() {
        Lead l = siteLead();
        LeadConvertResponse r = service.convert(l.getId(), withClient(client(Market.KZ).getId()), "admin");

        PrivateRequestResponse pr = privateRequestController.findById(r.getPrivateRequestId());

        assertThat(pr.getLead()).isNotNull();
        assertThat(pr.getLead().getId()).isEqualTo(l.getId());
        assertThat(pr.getLead().getSubject()).isEqualTo("Запрос КП");
        assertThat(pr.getLead().getSource()).isEqualTo("westmed.kz");
    }
}
