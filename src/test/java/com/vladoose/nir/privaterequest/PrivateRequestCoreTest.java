package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.response.PrivateRequestLineView;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PrivateRequestCoreTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestViewBuilder viewBuilder;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired LeadIntakeService intake;
    @Autowired ClientOfferService clientOfferService;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Facility facility(String name, Market market) {
        return facilityRepository.save(Facility.builder().name(name).market(market).build());
    }

    private TenderLot lot(Tender t, int no, String name) {
        TenderLot l = TenderLot.builder().tender(t).lotNumber(no).equipName(name).quantity(1).build();
        t.getLots().add(l);
        em.persist(l);   // saveAndFlush(t) = merge управляемой заявки: в коллекцию встала бы копия, а l остался бы transient
        em.flush();
        return l;
    }

    private PriceRequest requestPrices(Tender t, TenderLot lot) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Ядро поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1).build());
        return pr;
    }

    @Test
    void createEmpty_numberStatusMarketDate() {
        Tender t = service.createEmpty();

        assertThat(t.getTenderNumber()).matches("ЧЗ-" + LocalDate.now(Market.KZ.zone()).getYear() + "-\\d{4}");
        assertThat(t.getStatus()).isEqualTo("NEW");
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getLots()).isEmpty();
    }

    @Test
    void numberIsMaxPlusOne_noDuplicateAfterDelete() {
        Tender a = service.createEmpty();
        Tender b = service.createEmpty();
        service.delete(a.getId(), "admin");
        em.flush();

        Tender c = service.createEmpty();

        int nb = Integer.parseInt(b.getTenderNumber().substring(b.getTenderNumber().lastIndexOf('-') + 1));
        int nc = Integer.parseInt(c.getTenderNumber().substring(c.getTenderNumber().lastIndexOf('-') + 1));
        assertThat(nc).isEqualTo(nb + 1);
    }

    @Test
    void findById_otherMarketOrPublicTender_is404() {
        Tender kz = service.createEmpty();
        Tender publicTender = tenderRepository.save(Tender.builder().tenderNumber("ZZ-PUB-1").status("ACTIVE").build());

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.findById(kz.getId())).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> service.findById(publicTender.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void facilityAndCustomerNameReplaceEachOther() {
        Tender t = service.createEmpty();
        Facility f = facility("ZZ Ядро клиника " + System.nanoTime(), Market.KZ);

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "  Частная клиника, Уральск ");
        assertThat(t.getCustomerName()).isEqualTo("Частная клиника, Уральск");
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Частная клиника, Уральск");

        service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(f.getId()));
        assertThat(t.getFacility().getId()).isEqualTo(f.getId());
        assertThat(t.getCustomerName()).isNull();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ТОО «SP NEFT»");
        assertThat(t.getFacility()).isNull();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "  ");
        assertThat(t.getCustomerName()).isNull();
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Клиент не указан");
    }

    @Test
    void facilityOfOtherMarket_is404() {
        Tender t = service.createEmpty();
        Facility rf = facility("ZZ Ядро РФ клиника " + System.nanoTime(), Market.RF);

        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(rf.getId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void contactFieldsAreValidated() {
        Tender t = service.createEmpty();

        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "Айгуль Сапарова");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 234 56 78");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_EMAIL, "aigul@clinic.kz");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        service.updateField(t.getId(), PrivateRequestField.NOTE, "Нужно к 1 ноября");
        assertThat(t.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(t.getContactPhone()).isEqualTo("+7 701 234 56 78");
        assertThat(t.getContactEmail()).isEqualTo("aigul@clinic.kz");
        assertThat(t.getDeliveryAddress()).isEqualTo("г. Уральск");
        assertThat(t.getDescription()).isEqualTo("Нужно к 1 ноября");
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Айгуль Сапарова");

        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.CONTACT_EMAIL, "не почта"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("почты");
        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "1".repeat(51)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Телефон");
        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.FACILITY, "abc"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void saveClient_createsFacilityAndLinks() {
        Tender t = service.createEmpty();
        String name = "ZZ Новая клиника " + System.nanoTime();
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, name);
        service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 000 00 01");

        service.saveClient(t.getId());

        assertThat(t.getFacility()).isNotNull();
        assertThat(t.getFacility().getName()).isEqualTo(name);
        assertThat(t.getFacility().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getFacility().getPhone()).isEqualTo("+7 701 000 00 01");
        assertThat(t.getCustomerName()).isNull();
    }

    @Test
    void saveClient_sameMarketNameLinksExisting_otherMarketNameIs400() {
        Facility existing = facility("ZZ Есть такая " + System.nanoTime(), Market.KZ);
        Facility rf = facility("ZZ Занято в РФ " + System.nanoTime(), Market.RF);
        Tender t = service.createEmpty();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, existing.getName());
        service.saveClient(t.getId());
        assertThat(t.getFacility().getId()).isEqualTo(existing.getId());

        Tender other = service.createEmpty();
        service.updateField(other.getId(), PrivateRequestField.CUSTOMER_NAME, rf.getName());
        assertThatThrownBy(() -> service.saveClient(other.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("другого рынка");

        Tender empty = service.createEmpty();
        assertThatThrownBy(() -> service.saveClient(empty.getId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void closeAndReopen() {
        Tender t = service.createEmpty();

        service.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(t.getStatus()).isEqualTo("WON");
        service.reopen(t.getId());
        assertThat(t.getStatus()).isEqualTo("NEW");
        assertThatThrownBy(() -> service.close(t.getId(), PrivateRequestStatus.NEW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.close(t.getId(), null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void delete_blockedByPriceRequestOrClientOffer() {
        Tender withPr = service.createEmpty();
        requestPrices(withPr, lot(withPr, 1, "ZZ Позиция"));
        assertThatThrownBy(() -> service.delete(withPr.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("запросы цен");

        Tender withOffer = service.createEmpty();
        ClientOffer offer = clientOfferService.create("admin");
        offer.setTenderId(withOffer.getId());
        em.flush();
        assertThatThrownBy(() -> service.delete(withOffer.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("КП клиенту");
    }

    @Test
    void delete_returnsLeadToWork() {
        Tender t = service.createEmpty();
        Lead lead = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-core-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", "+7 777 000 00 09", null, null, "Нужен облучатель",
                List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();
        lead.setPrivateRequest(t);
        lead.setStatus(LeadStatus.CONVERTED);
        em.flush();

        service.delete(t.getId(), "admin");
        em.flush();

        assertThat(tenderRepository.findById(t.getId())).isEmpty();
        assertThat(lead.getPrivateRequest()).isNull();
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).anyMatch(e -> e.getBody() != null && e.getBody().contains("заявка " + t.getTenderNumber() + " удалена"));
    }

    @Test
    void view_hasLeadLinesRequestedAndRegistry() {
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-VIEW-RU-1").name("Аппарат ZZVIEWMODEL")
                .producer("ZZVIEWVENDOR").country("ЯПОНИЯ").unlimited(true).build());
        Tender t = service.createEmpty();
        TenderLot second = lot(t, 2, "Вторая позиция");
        TenderLot first = lot(t, 1, "Первая позиция");
        first.setRegStatus(LineRegStatus.CONFIRMED);
        first.setRegNumber("ZZ-VIEW-RU-1");
        requestPrices(t, second);
        em.flush();

        PrivateRequestViewResponse v = viewBuilder.build(t);

        assertThat(v.getNumber()).isEqualTo(t.getTenderNumber());
        assertThat(v.getStatus()).isEqualTo("NEW");
        assertThat(v.getLines()).extracting(PrivateRequestLineView::getName).containsExactly("Первая позиция", "Вторая позиция");
        assertThat(v.getLines().get(0).isRequested()).isFalse();
        assertThat(v.getLines().get(1).isRequested()).isTrue();
        assertThat(v.getLines().get(0).getRegStatus()).isEqualTo("CONFIRMED");
        assertThat(v.getLines().get(0).getRegistry().getProducer()).isEqualTo("ZZVIEWVENDOR");
        assertThat(v.getLines().get(1).getRegistry()).isNull();
    }
}
