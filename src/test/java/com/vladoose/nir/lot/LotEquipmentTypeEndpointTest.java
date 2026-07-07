package com.vladoose.nir.lot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.EquipmentTypeAssignRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class LotEquipmentTypeEndpointTest {

    @Autowired TenderLotController controller;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired EquipmentTypeRepository typeRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot lotIn(Market market) {
        MarketContext.set(market);
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("T-" + market + "-" + System.nanoTime())
                .status("NEW").market(market).build());
        return lotRepository.save(TenderLot.builder()
                .tender(t).lotNumber(1).equipName("Аппарат ИВЛ реанимационный").quantity(1).build());
    }

    @Test
    void setsType() {
        TenderLot lot = lotIn(Market.KZ);
        EquipmentType type = typeRepository.findAll().stream()
                .filter(x -> x.getName().equals("ИВЛ")).findFirst().orElseThrow();
        EquipmentTypeAssignRequest req = new EquipmentTypeAssignRequest();
        req.setTypeId(type.getId());

        MarketContext.set(Market.KZ);
        controller.setEquipmentType(lot.getId(), req);

        assertThat(lotRepository.findById(lot.getId()).orElseThrow().getEquipmentType().getName()).isEqualTo("ИВЛ");
    }

    @Test
    void rejectsForeignMarket() {
        TenderLot lot = lotIn(Market.KZ);
        EquipmentTypeAssignRequest req = new EquipmentTypeAssignRequest();
        req.setTypeId(typeRepository.findAll().get(0).getId());

        MarketContext.set(Market.RF);   // чужой рынок
        assertThatThrownBy(() -> controller.setEquipmentType(lot.getId(), req))
                .isInstanceOf(NotFoundException.class);
    }
}
