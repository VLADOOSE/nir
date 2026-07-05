package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.util.LotQueryTokenizer;
import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import com.vladoose.nir.util.TechSpecExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class RegistryMatchService {

    private final MedRegistryRepository registryRepository;
    private final MedEquipmentRepository equipmentRepository;
    private final TenderLotRepository tenderLotRepository;

    public RegistryMatchService(MedRegistryRepository registryRepository,
                                MedEquipmentRepository equipmentRepository,
                                TenderLotRepository tenderLotRepository) {
        this.registryRepository = registryRepository;
        this.equipmentRepository = equipmentRepository;
        this.tenderLotRepository = tenderLotRepository;
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
                .map(this::toCandidate)
                .toList();
    }

    private RegistryCandidateResponse toCandidate(com.vladoose.nir.dto.response.RegistryCandidateRow row) {
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
    }

    public List<RegistryCandidateResponse> candidatesForEquipment(Long equipmentId, int limit) {
        MedEquipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + equipmentId));
        return findCandidates(e.getName(), e.getManufact(), limit);
    }

    /** Общий матч по лоту: бренд задан → бренд-путь; иначе значимые токены имени (+ токены из
     *  характеристик разобранного ТЗ, вес ×0.5) → пословный триграммный матч. Флаг distinctive —
     *  есть ли чем различать записи (≥2 токена или задан бренд); при 1 токене процент врёт. */
    private record LotMatch(List<RegistryCandidateResponse> candidates, boolean distinctive, boolean techSpecParsed) {}

    private LotMatch computeLotMatch(TenderLot lot, int limit) {
        String chars = TechSpecExtractor.characteristics(lot.getRequiredSpec()); // один раз (может быть большой текст)
        boolean techSpecParsed = chars != null;
        if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
            return new LotMatch(findCandidates(lot.getEquipName(), lot.getManufact(), limit), true, techSpecParsed);
        }
        List<WeightedToken> tokens = LotQueryTokenizer.tokenize(lot.getEquipName(), chars);
        if (tokens.isEmpty()) {
            return new LotMatch(findCandidates(lot.getEquipName(), lot.getManufact(), limit), false, techSpecParsed);
        }
        String toks = tokens.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        String wgts = tokens.stream()
                .map(t -> String.format(Locale.ROOT, "%.2f", t.weight()))
                .collect(Collectors.joining("|"));
        List<RegistryCandidateResponse> candidates = registryRepository.searchByTokens(toks, wgts, limit).stream()
                .map(this::toCandidate)
                .toList();
        // ≥2 значимых токена → есть чем различать записи; 1 токен → совпадение только по названию
        return new LotMatch(candidates, tokens.size() >= 2, techSpecParsed);
    }

    /** Кандидаты реестра по лоту (для LotSourcingService) — прежний контракт. */
    public List<RegistryCandidateResponse> candidatesForLot(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        return computeLotMatch(lot, limit).candidates();
    }

    /** Для панели «Реестр»: кандидаты + метаданные достоверности матча (distinctive/techSpecParsed). */
    public LotRegistryMatchResponse matchForLotUi(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        LotMatch m = computeLotMatch(lot, limit);
        LotRegistryMatchResponse r = new LotRegistryMatchResponse();
        r.setCandidates(m.candidates());
        r.setDistinctive(m.distinctive());
        r.setTechSpecParsed(m.techSpecParsed());
        return r;
    }

    /**
     * «Взять из реестра в работу»: РУ → позиция каталога (create/reuse) → предложенная модель лота.
     * Каталог KZ наполняется по ходу работы с тендерами; оператор подтверждает кандидата вручную.
     */
    @Transactional
    public TenderLot adoptForLot(Long lotId, String regNumber) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        // findById = em.find обходит фильтр рынка → явный гард (паттерн proposed-equipment)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));

        MedEquipment eq = equipmentRepository.findFirstByRegistrationRegNumber(regNumber)
                .orElseGet(() -> {
                    MedEquipment e = new MedEquipment();
                    e.setName(trim255(reg.getName()));
                    e.setManufact(reg.getProducer() != null && !reg.getProducer().isBlank()
                            ? trim255(reg.getProducer()) : "не указан");
                    if (reg.getTechChars() != null && !reg.getTechChars().isBlank()) {
                        e.setSpec(reg.getTechChars()); // из кеша карточки НЦЭЛС; внешку при adopt не зовём
                    }
                    e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                    e.setRegistration(reg);
                    e.setRegistrationCheckedAt(OffsetDateTime.now());
                    e.setMarket(MarketContext.get()); // пред-штамп (defense-in-depth к листенеру)
                    return equipmentRepository.save(e);
                });

        lot.setProposedEquipment(eq);
        return tenderLotRepository.save(lot);
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
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
