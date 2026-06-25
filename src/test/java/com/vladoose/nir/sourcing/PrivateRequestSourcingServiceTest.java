package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.SourcingPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestSourcingServiceTest {

    @Autowired PrivateRequestSourcingService sourcing;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void buildSourcing_groupsByBrand_andListsUnmatched() {
        // поставщик с брендом Mindray
        distributorRepository.save(Distributor.builder()
                .name("ZZSRC МедСнаб").brands(new ArrayList<>(List.of("Mindray"))).build());
        // заявка: строка Mindray (закрывается) + строка КриоСпейс (нет поставщика)
        Long clientId = facilityRepository.save(Facility.builder().name("ZZSRC Клиника").build()).getId();
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(clientId);
        PrivateRequestCreate.Line l1 = new PrivateRequestCreate.Line();
        l1.setName("Электрокардиограф BeneHeart R12"); l1.setManufact("Mindray"); l1.setQuantity(2);
        PrivateRequestCreate.Line l2 = new PrivateRequestCreate.Line();
        l2.setName("Криосауна CryoSpace"); l2.setManufact("КриоСпейс"); l2.setQuantity(1);
        dto.setLines(List.of(l1, l2));
        Tender t = privateRequestService.createFromLines(dto);

        SourcingPreviewResponse preview = sourcing.buildSourcing(t.getId());

        assertThat(preview.getGroups()).hasSize(1);
        assertThat(preview.getGroups().get(0).getDistributor().getName()).isEqualTo("ZZSRC МедСнаб");
        assertThat(preview.getGroups().get(0).getLines())
                .extracting(line -> line.getManufact()).containsExactly("Mindray");
        assertThat(preview.getUnmatchedLines())
                .extracting(line -> line.getManufact()).containsExactly("КриоСпейс");
    }
}
