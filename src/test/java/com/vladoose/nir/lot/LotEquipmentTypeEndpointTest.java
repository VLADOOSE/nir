package com.vladoose.nir.lot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.EquipmentTypeAssignRequest;
import com.vladoose.nir.dto.request.TenderLotRequest;
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

    /**
     * {@code PUT /api/lots/{id}} — тот же гард чужого рынка, что у соседей.
     *
     * <p>{@code TenderLot} НЕ market-scoped (рынок живёт на тендере), поэтому
     * {@code findById} по client-supplied id достаёт лот чужого рынка в обход
     * hibernate-фильтра. Дыра преэкзистующая, но эта ветка провела через неё
     * ОДНОСТОРОННЮЮ запись: непустое новое {@code requiredSpec} ставит
     * {@code techSpecStatus = OK}, а это замораживает описание от обновления при переимпорте
     * и навсегда убирает лот из фоновой очереди разбора ТЗ (очередь берёт IS NULL/PENDING).
     * Отката нет — отсюда гард.
     */
    @Test
    void updateRejectsForeignMarket() {
        TenderLot lot = lotIn(Market.KZ);
        TenderLotRequest req = new TenderLotRequest();
        req.setEquipName("Подменённое имя");
        req.setRequiredSpec("Техспека, вписанная с чужого рынка");
        req.setQuantity(1);

        MarketContext.set(Market.RF);   // чужой рынок
        assertThatThrownBy(() -> controller.update(lot.getId(), req))
                .isInstanceOf(NotFoundException.class);

        MarketContext.set(Market.KZ);
        TenderLot after = lotRepository.findById(lot.getId()).orElseThrow();
        assertThat(after.getEquipName()).isEqualTo("Аппарат ИВЛ реанимационный");
        assertThat(after.getTechSpecStatus())
                .as("статус ТЗ не проставлен: иначе лот молча выпал бы из очереди разбора")
                .isNull();
    }
}
