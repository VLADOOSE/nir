package com.vladoose.nir.tender;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.ProposedEquipmentRequest;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class LotProposedEquipmentTest {

    @Autowired TenderLotController controller;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    private TenderLot makeLot() {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-PROP-" + System.nanoTime());
        t.setStatus("ACTIVE");
        tenderRepository.save(t);
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setLotNumber(1);
        lot.setEquipName("ZZ Аппарат УЗИ");
        lot.setQuantity(2);
        return tenderLotRepository.save(lot);
    }

    private MedEquipment makeEquipment(String name) {
        MedEquipment e = new MedEquipment();
        e.setName(name);
        e.setManufact("Mindray");
        return medEquipmentRepository.save(e);
    }

    @Test
    void setReplaceAndClearProposedEquipment() {
        MarketContext.set(Market.KZ);
        TenderLot lot = makeLot();
        MedEquipment eq1 = makeEquipment("ZZ SonoMax 1");
        MedEquipment eq2 = makeEquipment("ZZ SonoMax 2");

        ProposedEquipmentRequest req = new ProposedEquipmentRequest();
        req.setEquipmentId(eq1.getId());
        TenderLotResponse r1 = controller.setProposedEquipment(lot.getId(), req);
        assertThat(r1.getProposedEquipment()).isNotNull();
        assertThat(r1.getProposedEquipment().getId()).isEqualTo(eq1.getId());
        assertThat(r1.getProposedEquipment().getManufact()).isEqualTo("Mindray");

        req.setEquipmentId(eq2.getId());
        TenderLotResponse r2 = controller.setProposedEquipment(lot.getId(), req);
        assertThat(r2.getProposedEquipment().getId()).isEqualTo(eq2.getId());

        TenderLotResponse r3 = controller.clearProposedEquipment(lot.getId());
        assertThat(r3.getProposedEquipment()).isNull();
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getProposedEquipment()).isNull();
    }

    @Test
    void equipmentFromOtherMarketIsRejected() {
        MarketContext.set(Market.RF);
        MedEquipment rfEq = makeEquipment("ZZ RF-Only");
        MarketContext.set(Market.KZ);
        TenderLot lot = makeLot();

        ProposedEquipmentRequest req = new ProposedEquipmentRequest();
        req.setEquipmentId(rfEq.getId());
        assertThatThrownBy(() -> controller.setProposedEquipment(lot.getId(), req))
                .isInstanceOf(NotFoundException.class);
    }
}
