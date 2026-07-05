package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class RegistryDetailServiceTest {

    @Autowired RegistryDetailService service;
    @Autowired MedRegistryRepository registryRepository;
    @MockitoBean NddaClient nddaClient;

    MedRegistry reg;

    @BeforeEach
    void setUp() {
        reg = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-DETAIL-1")
                .name("ZZ Тестовое изделие для детали")
                .build());
    }

    private NddaDetailDto dto() {
        NddaDetailDto d = new NddaDetailDto();
        d.setPurpose("Поддерживает глубину передней камеры");
        d.setUseArea("Офтальмологическая хирургия");
        d.setDegreeRiskName("Класс 2 б");
        d.setShortTechnicalCharacteristicsRu("Вязкость 40 000 мПа·с");
        d.setTermNameRus("Материал для замещения");
        d.setTermDefinition("Неимплантируемое вещество");
        return d;
    }

    @Test
    void firstCall_fetchesFromNdda_andCaches() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenReturn(182621L);
        when(nddaClient.fetchDetail(182621L)).thenReturn(dto());

        RegistryDetailResponse r = service.detail("ZZ-РУ-DETAIL-1");

        assertThat(r.getRiskClass()).isEqualTo("Класс 2 б");
        assertThat(r.getPurpose()).contains("глубину передней камеры");
        assertThat(r.getTechChars()).contains("40 000");
        assertThat(r.getMiKind()).isEqualTo("Материал для замещения");
        assertThat(r.getFetchedAt()).isNotNull();

        MedRegistry cached = registryRepository.findByRegNumber("ZZ-РУ-DETAIL-1").orElseThrow();
        assertThat(cached.getTechChars()).contains("40 000");
        assertThat(cached.getNddaId()).isEqualTo(182621L);
        assertThat(cached.getDetailFetchedAt()).isNotNull();
    }

    @Test
    void secondCall_servedFromCache_withoutNetwork() {
        when(nddaClient.resolveId(anyString())).thenReturn(182621L);
        when(nddaClient.fetchDetail(anyLong())).thenReturn(dto());

        service.detail("ZZ-РУ-DETAIL-1");
        RegistryDetailResponse r2 = service.detail("ZZ-РУ-DETAIL-1");

        assertThat(r2.getTechChars()).contains("40 000");
        verify(nddaClient, times(1)).resolveId(anyString());
        verify(nddaClient, times(1)).fetchDetail(anyLong());
    }

    @Test
    void notFoundOnPortal_cachesEmptyMarker_andDoesNotRetry() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenReturn(null);

        RegistryDetailResponse r = service.detail("ZZ-РУ-DETAIL-1");
        assertThat(r.getPurpose()).isNull();
        assertThat(r.getTechChars()).isNull();
        assertThat(r.getFetchedAt()).isNotNull();

        service.detail("ZZ-РУ-DETAIL-1"); // второй вызов — из кеша
        verify(nddaClient, times(1)).resolveId(anyString());
        verify(nddaClient, never()).fetchDetail(anyLong());
    }

    @Test
    void unknownRegNumber_throws404() {
        assertThatThrownBy(() -> service.detail("НЕТ-ТАКОГО-РУ"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void upstreamError_propagates_andCacheStaysEmpty() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenThrow(new UpstreamException("НЦЭЛС недоступен"));

        assertThatThrownBy(() -> service.detail("ZZ-РУ-DETAIL-1"))
                .isInstanceOf(UpstreamException.class);

        MedRegistry after = registryRepository.findByRegNumber("ZZ-РУ-DETAIL-1").orElseThrow();
        assertThat(after.getDetailFetchedAt()).isNull(); // следующий клик = retry
    }
}
