package com.vladoose.nir.imports;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.ImportLinesRequest;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
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
class PrivateRequestImportServiceTest {

    @Autowired PrivateRequestImportService importService;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired HeaderSynonymRepository synonymRepository;
    @Autowired FacilityRepository facilityRepository;

    @Test
    void commit_savesNewSynonym_andCreatesRequestWithLines() {
        Long clientId = facilityRepository.save(Facility.builder().name("ZZIMP Клиника").build()).getId();

        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setClientFacilityId(clientId);
        ColumnMapping m1 = new ColumnMapping(); m1.setHeader("Изделие"); m1.setField(LineField.NAME);
        ColumnMapping m2 = new ColumnMapping(); m2.setHeader("Вендор"); m2.setField(LineField.MANUFACT);
        ColumnMapping m3 = new ColumnMapping(); m3.setHeader("Лишнее"); m3.setField(LineField.IGNORE);
        dto.setMappings(List.of(m1, m2, m3));
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Аппарат X"); line.setManufact("BrandY"); line.setQuantity(3);
        dto.setLines(List.of(line));

        Tender t = importService.commit(dto);

        assertThat(synonymRepository.findByHeaderNorm("изделие")).get()
                .extracting(HeaderSynonym::getField).isEqualTo(LineField.NAME);
        // IGNORE не сохраняется как синоним
        assertThat(synonymRepository.findByHeaderNorm("лишнее")).isEmpty();
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getLots()).extracting(com.vladoose.nir.entity.TenderLot::getManufact).containsExactly("BrandY");
    }

    @Test
    void commit_updatesExistingSynonymField() {
        synonymRepository.save(HeaderSynonym.builder().headerNorm("поставка").field(LineField.NAME).build());
        synonymRepository.flush();
        Long clientId = facilityRepository.save(Facility.builder().name("ZZIMP2 Клиника").build()).getId();

        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setClientFacilityId(clientId);
        ColumnMapping m = new ColumnMapping(); m.setHeader("Поставка"); m.setField(LineField.MANUFACT);
        dto.setMappings(List.of(m));
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Z"); line.setQuantity(1);
        dto.setLines(List.of(line));

        importService.commit(dto);

        assertThat(synonymRepository.findByHeaderNorm("поставка")).get()
                .extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }

    @AfterEach void clearMarket() { MarketContext.clear(); }

    @Test
    void commit_withoutClient_takesContactFromLetter() {
        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setContactName("Айгуль Сапарова");
        dto.setContactEmail("aigul@clinic.kz");
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Тонометр"); line.setQuantity(2);
        dto.setLines(List.of(line));

        Tender t = importService.commit(dto);

        assertThat(t.getFacility()).isNull();
        assertThat(t.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(t.getContactEmail()).isEqualTo("aigul@clinic.kz");
        assertThat(t.getLots()).hasSize(1);
    }

    @Test
    void importInto_appendsOrReplaces_andLearnsHeaders() {
        MarketContext.set(Market.KZ);
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(PrivateRequestService.input("ZZ Было", null, 1, null, null, null)),
                LinesMode.APPEND);
        ImportLinesRequest dto = new ImportLinesRequest();
        ColumnMapping m = new ColumnMapping(); m.setHeader("ZZ Товар Импорт"); m.setField(LineField.NAME);
        dto.setMappings(List.of(m));
        PrivateRequestCreate.Line a = new PrivateRequestCreate.Line(); a.setName("ZZ Новое А"); a.setQuantity(-3);
        PrivateRequestCreate.Line b = new PrivateRequestCreate.Line(); b.setName("ZZ Новое Б"); b.setQuantity(4);
        dto.setLines(List.of(a, b));

        importService.importInto(t.getId(), dto);
        assertThat(t.getLots()).extracting(TenderLot::getEquipName).containsExactlyInAnyOrder("ZZ Было", "ZZ Новое А", "ZZ Новое Б");
        assertThat(t.getLots()).filteredOn(l -> l.getEquipName().equals("ZZ Новое А"))
                .singleElement().extracting(TenderLot::getQuantity).isEqualTo(1);
        assertThat(synonymRepository.findByHeaderNorm("zz товар импорт")).isPresent();

        dto.setMode(LinesMode.REPLACE);
        dto.setLines(List.of(b));
        importService.importInto(t.getId(), dto);
        assertThat(t.getLots()).extracting(TenderLot::getEquipName).containsExactly("ZZ Новое Б");
    }
}
