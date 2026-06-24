package com.vladoose.nir.privaterequest;

import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.TenderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderSourceFilterTest {

    @Autowired TenderService tenderService;
    @Autowired TenderRepository tenderRepository;

    @Test
    void findAll_excludesPrivateRequests() {
        Tender priv = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZPR-PRIV-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());   // deadline null допустим
        tenderRepository.flush();

        assertThat(tenderService.findAll())
                .extracting(Tender::getTenderNumber)
                .doesNotContain("ZZPR-PRIV-1");
        assertThat(tenderRepository.findBySource(Source.PRIVATE_REQUEST))
                .extracting(Tender::getTenderNumber)
                .contains("ZZPR-PRIV-1");
    }

    @Test
    void publicTender_savedWithNullableDeadline_andDefaultSource() {
        Tender pub = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZPR-PUB-1").status("NEW").build());
        tenderRepository.flush();
        assertThat(pub.getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(pub.getDeadline()).isNull();
    }
}
