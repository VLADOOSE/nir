package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** Запись кеша карточки НЦЭЛС отдельным транзакционным бином (сеть — вне транзакции, §6 CLAUDE.md). */
@Service
public class RegistryDetailWriter {

    private final MedRegistryRepository registryRepository;

    public RegistryDetailWriter(MedRegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    /** detail == null — РУ на портале не найден: ставим только маркер, чтобы не долбить портал повторно. */
    @Transactional
    public MedRegistry save(String regNumber, Long nddaId, NddaDetailDto detail) {
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        reg.setNddaId(nddaId);
        if (detail != null) {
            reg.setRiskClass(detail.getDegreeRiskName());
            reg.setPurpose(detail.getPurpose());
            reg.setUseArea(detail.getUseArea());
            reg.setTechChars(detail.getShortTechnicalCharacteristicsRu());
            reg.setMiKind(detail.getTermNameRus());
            reg.setMiKindDef(detail.getTermDefinition());
        }
        reg.setDetailFetchedAt(OffsetDateTime.now());
        return registryRepository.save(reg);
    }
}
