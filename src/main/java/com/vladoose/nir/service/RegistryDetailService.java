package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;

/**
 * Описание изделия из карточки НЦЭЛС: on-demand fetch при первом просмотре + кеш в med_registry.
 * Сеть — вне транзакции; запись кеша — RegistryDetailWriter (паттерн TechSpecService/TechSpecWriter).
 */
@Service
public class RegistryDetailService {

    private final MedRegistryRepository registryRepository;
    private final NddaClient nddaClient;
    private final RegistryDetailWriter writer;

    public RegistryDetailService(MedRegistryRepository registryRepository,
                                 NddaClient nddaClient,
                                 RegistryDetailWriter writer) {
        this.registryRepository = registryRepository;
        this.nddaClient = nddaClient;
        this.writer = writer;
    }

    public RegistryDetailResponse detail(String regNumber) {
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        if (reg.getDetailFetchedAt() == null) {
            Long nddaId = nddaClient.resolveId(regNumber);                      // сеть вне транзакции
            NddaDetailDto detail = nddaId != null ? nddaClient.fetchDetail(nddaId) : null;
            reg = writer.save(regNumber, nddaId, detail);
        }
        return toResponse(reg);
    }

    private static RegistryDetailResponse toResponse(MedRegistry r) {
        return RegistryDetailResponse.builder()
                .regNumber(r.getRegNumber())
                .riskClass(r.getRiskClass())
                .purpose(r.getPurpose())
                .useArea(r.getUseArea())
                .techChars(r.getTechChars())
                .miKind(r.getMiKind())
                .miKindDef(r.getMiKindDef())
                .fetchedAt(r.getDetailFetchedAt())
                .build();
    }
}
