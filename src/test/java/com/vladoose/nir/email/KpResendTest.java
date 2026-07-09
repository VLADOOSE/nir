package com.vladoose.nir.email;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PriceRequestSendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class KpResendTest {

    @Autowired PriceRequestSendService sendService;
    @Autowired TenderRepository tenderRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired DistributorRepository distributorRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private PriceRequest prNoEmail(Market market) {
        MarketContext.set(market);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + market + "-" + System.nanoTime())
                .status("NEW").market(market).source(Source.PUBLIC_TENDER).build());
        // distributor.distributor_id FK — nullable=false, без каскада из PR → сохраняем явно
        Distributor d = distributorRepository.save(
                Distributor.builder().name("Дист " + System.nanoTime()).market(market).build()); // без email
        return priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d)
                .market(market).status("SENT").build());
    }

    @Test
    void resendForeignMarketRejected() {
        PriceRequest pr = prNoEmail(Market.KZ);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> sendService.resend(pr.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resendNoEmailReturnsReasonNotThrows() {
        PriceRequest pr = prNoEmail(Market.KZ);
        MarketContext.set(Market.KZ);
        PriceRequestSendService.SendResult r = sendService.resend(pr.getId());
        assertThat(r.priceRequestId()).isEqualTo(pr.getId());
        assertThat(r.emailSent()).isFalse();
        assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_NO_EMAIL);
        // новый PR не создан — тот же id
        assertThat(priceRequestRepository.findById(pr.getId())).isPresent();
    }
}
