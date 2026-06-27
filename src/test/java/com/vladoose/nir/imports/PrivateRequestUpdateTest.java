package com.vladoose.nir.imports;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.request.PrivateRequestUpdate;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderLotRepository;
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
class PrivateRequestUpdateTest {

    @Autowired PrivateRequestService service;
    @Autowired FacilityRepository facilityRepository;
    @Autowired TenderLotRepository tenderLotRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void update_editsManufact_addsLine_removesLine() {
        MarketContext.set(Market.KZ);
        Long fid = facilityRepository.save(Facility.builder().name("ZZUPD Клиника").build()).getId();
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(fid);
        PrivateRequestCreate.Line l1 = new PrivateRequestCreate.Line();
        l1.setName("Монитор"); l1.setQuantity(1);
        PrivateRequestCreate.Line l2 = new PrivateRequestCreate.Line();
        l2.setName("Дефибриллятор"); l2.setQuantity(1);
        dto.setLines(List.of(l1, l2));
        Tender t = service.createFromLines(dto);
        Long monitorLotId = tenderLotRepository.findByTenderId(t.getId()).stream()
                .filter(x -> "Монитор".equals(x.getEquipName())).findFirst().orElseThrow().getId();

        // правим Монитор (добавляем бренд + кол-во), удаляем Дефибриллятор, добавляем новую строку
        PrivateRequestUpdate upd = new PrivateRequestUpdate();
        PrivateRequestUpdate.Line e1 = new PrivateRequestUpdate.Line();
        e1.setLotId(monitorLotId); e1.setName("Монитор пациента"); e1.setManufact("Mindray"); e1.setQuantity(5);
        PrivateRequestUpdate.Line e2 = new PrivateRequestUpdate.Line();
        e2.setName("Тонометр"); e2.setManufact("OMRON"); e2.setQuantity(2);   // новая
        upd.setLines(List.of(e1, e2));
        service.update(t.getId(), upd);

        List<TenderLot> after = tenderLotRepository.findByTenderId(t.getId());
        assertThat(after).extracting(TenderLot::getEquipName)
                .containsExactlyInAnyOrder("Монитор пациента", "Тонометр");
        TenderLot monitor = after.stream()
                .filter(x -> "Монитор пациента".equals(x.getEquipName())).findFirst().orElseThrow();
        assertThat(monitor.getId()).isEqualTo(monitorLotId);   // та же строка (правка, не пересоздание)
        assertThat(monitor.getManufact()).isEqualTo("Mindray");
        assertThat(monitor.getQuantity()).isEqualTo(5);
    }
}
