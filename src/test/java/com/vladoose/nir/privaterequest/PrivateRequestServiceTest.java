package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestServiceTest {

    @Autowired PrivateRequestService service;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Long client() {
        return facilityRepository.save(Facility.builder().name("ZZPRS Клиника").build()).getId();
    }

    @Test
    void createFromLines_buildsPrivateTenderWithLotsAndAutoNumber() {
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(client());
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName("Тонометр OMRON M2"); l.setManufact("OMRON"); l.setQuantity(3);
        dto.setLines(List.of(l));

        Tender t = service.createFromLines(dto);

        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getTenderNumber()).startsWith("ЧЗ-");
        assertThat(t.getLots()).hasSize(1);
        TenderLot lot = t.getLots().get(0);
        assertThat(lot.getEquipName()).isEqualTo("Тонометр OMRON M2");
        assertThat(lot.getManufact()).isEqualTo("OMRON");
        assertThat(lot.getQuantity()).isEqualTo(3);
    }
}
