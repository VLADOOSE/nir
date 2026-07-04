package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.FacilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MarketStampingTest {

    @Autowired FacilityService facilityService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void create_stampsActiveMarket_KZ() {
        MarketContext.set(Market.KZ);
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-KZ учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void create_defaultsToRF_whenNoContext() {
        MarketContext.clear();
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-RF учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.RF);
    }

    @Test
    void update_preservesMarket_doesNotRestamp() {
        MarketContext.set(Market.KZ);
        Facility f = facilityService.save(Facility.builder().name("ZZUPD-KZ учреждение").build());
        assertThat(f.getMarket()).isEqualTo(Market.KZ);
        // переключаемся на RF и сохраняем существующую (id!=null) — рынок должен сохраниться KZ
        MarketContext.set(Market.RF);
        f.setAddress("обновлённый адрес");
        Facility updated = facilityService.save(f);
        assertThat(updated.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void directRepositorySave_stampsActiveMarket_viaListener() {
        MarketContext.set(Market.KZ);
        // создаём родителя-тендер и дистрибьютора напрямую (тоже через листенер -> KZ)
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZLST-KZ").status("NEW")
                .deadline(java.time.LocalDate.of(2026, 12, 1)).build());
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZLST-KZ дистр").build());
        // PriceRequest напрямую через репозиторий (минуя сервис) — проверяем штамп рынка листенером
        PriceRequest pr = priceRequestRepository.save(
                PriceRequest.builder()
                    .tender(t).distributor(d).status("CREATED").build());
        assertThat(pr.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
    }
}
