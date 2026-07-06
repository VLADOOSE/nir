package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ComplectSearchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class ComplectServiceTest {

    @Autowired ComplectService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired RegistryComponentRepository componentRepository;
    @MockitoBean NddaClient nddaClient;

    MedRegistry apparatus;
    Long lotId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        // Синтетический бренд ДОЛЖЕН быть герметичен: изначальный «ZZFOOBRAND» триграммно
        // совпадает с реальной записью реестра «АСЭтМ-01/6-«ЭЛЭСКУЛАП-Мед ТеКо»» (word_similarity
        // 0.75 ≥ 0.6) → findApparatusByTerm возвращал 2 аппарата, search#1 честно тянул комплектность
        // для обоих (fetch ×2) и ломал verify(times(1)). Латинский токен не пересекается с
        // кириллическим реестром ни сейчас, ни на будущих снапшотах данных (проверено: 0 совпадений).
        apparatus = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РК МИ (МТ)-COMPLECT-1")
                .name("ZZ Аппарат электротерапии ZZFOOBRAND Мед ТеКо")
                .producer("Мед ТеКо").country("РОССИЯ").build());
        Tender t = new Tender();
        t.setTenderNumber("ZZ-COMPLECT-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName("Электрод");
        l.setRequiredSpec("Резиновые пластинки для аппарата электрофореза \"ZZFOOBRAND\", силиконовые 55*80 мм");
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        lotId = t.getLots().get(0).getId();
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    private List<NddaComplectItemDto> components() {
        NddaComplectItemDto a = new NddaComplectItemDto();
        a.setPartNumber(2); a.setComponent("комплектующие");
        a.setProductName("2.Электроды токопроводящие терапевтические: 40 х 50; 90 х 140;");
        a.setProducerName("ООО «Мед ТеКо»"); a.setCountryName("Россия");
        NddaComplectItemDto b = new NddaComplectItemDto();
        b.setPartNumber(4); b.setComponent("комплектующие");
        b.setProductName("4.Электроды силиконовые электропроводящие, мм: 25 х 30; 55 х 80; 100 х 120;");
        b.setProducerName("ООО «Мед ТеКо»"); b.setCountryName("Россия");
        return List.of(a, b);
    }

    @Test
    void search_findsApparatus_fetchesComplect_ranksSiliconeFirst() {
        when(nddaClient.resolveId("ZZ-РК МИ (МТ)-COMPLECT-1")).thenReturn(178624L);
        when(nddaClient.fetchComplectList(178624L)).thenReturn(components());

        ComplectSearchResponse r = service.search(lotId, null);

        assertThat(r.getTerm()).isEqualTo("ZZFOOBRAND");
        assertThat(r.getApparatuses()).isNotEmpty();
        var comps = r.getApparatuses().get(0).getComponents();
        assertThat(comps.get(0).getPartNumber()).isEqualTo(4);            // силиконовый 55×80 — первым
        assertThat(comps.get(0).getScore()).isGreaterThan(comps.get(1).getScore());
        assertThat(comps.get(0).getCountry()).isEqualTo("Россия");
        // компоненты закешированы
        assertThat(componentRepository.findByRegNumberOrderByPartNumber("ZZ-РК МИ (МТ)-COMPLECT-1")).hasSize(2);
    }

    @Test
    void search_secondCall_servedFromCache_withoutFetch() {
        when(nddaClient.resolveId(anyString())).thenReturn(178624L);
        when(nddaClient.fetchComplectList(anyLong())).thenReturn(components());

        service.search(lotId, null);
        service.search(lotId, null);

        verify(nddaClient, times(1)).fetchComplectList(anyLong()); // второй раз — из кеша
    }

    @Test
    void search_emptyTerm_noApparatus_noNetwork() {
        ComplectSearchResponse r = service.search(lotId, "zzzнетбренда");
        assertThat(r.getApparatuses()).isEmpty();
        verify(nddaClient, never()).fetchComplectList(anyLong());
    }
}
