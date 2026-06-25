package com.vladoose.nir.imports;

import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
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
        assertThat(privateRequestService.linesWithRegistration(t.getId()))
                .extracting("manufact").containsExactly("BrandY");
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
}
