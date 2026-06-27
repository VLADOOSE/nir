package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class RegistryMatchService {

    private final MedRegistryRepository registryRepository;
    private final MedEquipmentRepository equipmentRepository;

    public RegistryMatchService(MedRegistryRepository registryRepository,
                                MedEquipmentRepository equipmentRepository) {
        this.registryRepository = registryRepository;
        this.equipmentRepository = equipmentRepository;
    }

    /** Переиспользуемый примитив: (наименование, производитель) -> кандидаты реестра. */
    public List<RegistryCandidateResponse> findCandidates(String name, String manufact, int limit) {
        String n = name != null ? name : "";
        String m = manufact != null ? manufact : "";
        if (n.isBlank() && m.isBlank()) {
            return List.of();
        }
        // Длинные названия из смет (200+ симв.) → seq scan по реестру (~600мс): обрезаем до начала
        // (наименование изделия идёт первым; спецификация для матчинга не нужна) — быстрее и точнее.
        if (n.length() > 80) n = n.substring(0, 80);
        if (m.length() > 80) m = m.substring(0, 80);
        return registryRepository.findCandidates(n, m, limit).stream()
                .map(row -> {
                    RegistryCandidateResponse c = new RegistryCandidateResponse();
                    c.setRegNumber(row.getRegNumber());
                    c.setName(row.getName());
                    c.setProducer(row.getProducer());
                    c.setCountry(row.getCountry());
                    c.setRegDate(row.getRegDate());
                    c.setExpirationDate(row.getExpirationDate());
                    c.setUnlimited(row.getUnlimited());
                    c.setScore(row.getScore());
                    return c;
                })
                .toList();
    }

    public List<RegistryCandidateResponse> candidatesForEquipment(Long equipmentId, int limit) {
        MedEquipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + equipmentId));
        return findCandidates(e.getName(), e.getManufact(), limit);
    }

    @Transactional
    public MedEquipment applyAction(Long equipmentId, RegistrationAction action, String regNumber) {
        MedEquipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + equipmentId));
        switch (action) {
            case CONFIRM -> {
                if (regNumber == null || regNumber.isBlank()) {
                    throw new BadRequestException("Для подтверждения требуется regNumber");
                }
                MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                        .orElseThrow(() -> new BadRequestException("РУ не найдено в реестре: " + regNumber));
                e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                e.setRegistration(reg);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case NOT_REGISTERED -> {
                e.setRegistrationStatus(RegistrationStatus.NOT_REGISTERED);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case NOT_MEDICAL -> {
                e.setRegistrationStatus(RegistrationStatus.NOT_MEDICAL);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case RESET -> {
                e.setRegistrationStatus(RegistrationStatus.UNCHECKED);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(null);
            }
        }
        return equipmentRepository.save(e);
    }

    public List<ReconciliationRowResponse> buildReconciliation(String statusFilter, int candidatesPerRow) {
        List<ReconciliationRowResponse> rows = new ArrayList<>();
        for (MedEquipment e : equipmentRepository.findAll()) {
            RegistrationStatus status = e.getRegistrationStatus() != null
                    ? e.getRegistrationStatus() : RegistrationStatus.UNCHECKED;
            if (statusFilter != null && !statusFilter.isBlank()
                    && !status.name().equalsIgnoreCase(statusFilter)) {
                continue;
            }
            ReconciliationRowResponse row = new ReconciliationRowResponse();
            row.setEquipmentId(e.getId());
            row.setEquipmentName(e.getName());
            row.setManufact(e.getManufact());
            row.setEquipTypeName(e.getEquipmentType() != null ? e.getEquipmentType().getName() : null);
            row.setStatus(status.name());
            row.setVatExempt(status == RegistrationStatus.REGISTERED);
            row.setCurrentRegNumber(e.getRegistration() != null ? e.getRegistration().getRegNumber() : null);
            row.setCandidates(findCandidates(e.getName(), e.getManufact(), candidatesPerRow));
            rows.add(row);
        }
        return rows;
    }
}
