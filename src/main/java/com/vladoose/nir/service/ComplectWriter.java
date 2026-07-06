package com.vladoose.nir.service;

import com.vladoose.nir.entity.RegistryComponent;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Запись кеша комплектности отдельным транзакционным бином (сеть — вне транзакции, §6). */
@Service
public class ComplectWriter {

    private final RegistryComponentRepository componentRepository;
    private final MedRegistryRepository registryRepository;

    public ComplectWriter(RegistryComponentRepository componentRepository,
                          MedRegistryRepository registryRepository) {
        this.componentRepository = componentRepository;
        this.registryRepository = registryRepository;
    }

    /** Перезаписывает комплектность аппарата и бэкфилит ndda_id (чтобы не резолвить повторно). */
    @Transactional
    public void cache(String regNumber, Long nddaId, List<NddaComplectItemDto> items) {
        componentRepository.deleteByRegNumber(regNumber);
        componentRepository.flush(); // DELETE до INSERT'ов: иначе ActionQueue флашит INSERT перед DELETE → uq_registry_component
        for (NddaComplectItemDto it : items) {
            componentRepository.save(RegistryComponent.builder()
                    .regNumber(regNumber)
                    .partNumber(it.getPartNumber())
                    .productName(it.getProductName())
                    .component(it.getComponent())
                    .producer(it.getProducerName())
                    .country(it.getCountryName())
                    .fetchedAt(OffsetDateTime.now())
                    .build());
        }
        registryRepository.findByRegNumber(regNumber).ifPresent(reg -> {
            if (reg.getNddaId() == null) { reg.setNddaId(nddaId); registryRepository.save(reg); }
        });
    }
}
