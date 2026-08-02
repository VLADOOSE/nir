package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.dto.response.CannotReason;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.dto.response.TokenDfRow;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.util.LotQueryBuilder;
import com.vladoose.nir.util.LotQueryBuilder.LotQuery;
import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Пороги зон. Стартовые значения — из прототипа 2026-08-01; калибруются в Task 5. */
    static final double QUALIFIER_BONUS = 0.3;
    static final double SCORE_CUTOFF    = 0.2;
    static final double CONFIDENT_MIN   = 0.55;
    static final double SHORTLIST_MIN   = 0.30;

    /** Общий матч по лоту: бренд задан → бренд-путь; иначе identity-токены отбирают кандидатов,
     *  qualifier-токены (описание/ТЗ) их переранжируют. Зона честности считается из РЕЗУЛЬТАТА
     *  (скор топ-кандидата), а не из числа токенов запроса. */
    private record LotMatch(List<RegistryCandidateResponse> candidates,
                            MatchConfidence confidence,
                            CannotReason cannotReason,
                            boolean techSpecParsed) {}

    private LotMatch computeLotMatch(TenderLot lot, int limit) {
        LotQuery query = LotQueryBuilder.build(lot.getEquipName(), lot.getRequiredSpec());

        // Бренд задан оператором (частные заявки West-Med) — прежний бренд-путь, он не диагностирован как проблемный
        if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
            List<RegistryCandidateResponse> byBrand =
                    findCandidates(lot.getEquipName(), lot.getManufact(), limit);
            return new LotMatch(byBrand,
                    byBrand.isEmpty() ? MatchConfidence.CANNOT : MatchConfidence.CONFIDENT,
                    byBrand.isEmpty() ? CannotReason.NO_CANDIDATES : null,
                    query.techSpecParsed());
        }

        if (query.identity().isEmpty()) {
            return new LotMatch(List.of(), MatchConfidence.CANNOT,
                    CannotReason.NO_CANDIDATES, query.techSpecParsed());
        }

        List<WeightedToken> effective = withIdfWeights(query.identity());
        String toks = effective.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        String wgts = effective.stream()
                .map(t -> String.format(Locale.ROOT, "%.3f", t.weight()))
                .collect(Collectors.joining("|"));
        String quals = String.join("|", query.qualifier());

        List<RegistryCandidateResponse> candidates = registryRepository
                .searchByTokensV2(toks, wgts, quals, QUALIFIER_BONUS, SCORE_CUTOFF, limit).stream()
                .map(this::toCandidate)
                .toList();

        return new LotMatch(candidates,
                confidenceOf(candidates),
                cannotReasonOf(candidates, lot, query.techSpecParsed()),
                query.techSpecParsed());
    }

    /** Финальный вес = фактор источника × IDF ln((N+1)/(df+1)); токены с df=0 выкидываем (§8). */
    private List<WeightedToken> withIdfWeights(List<WeightedToken> tokens) {
        String allToks = tokens.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        Map<String, Long> df = registryRepository.tokenDocFreq(allToks).stream()
                .collect(Collectors.toMap(TokenDfRow::getTok, TokenDfRow::getDf, (a, b) -> a));
        List<WeightedToken> present = tokens.stream()
                .filter(t -> df.getOrDefault(t.token(), 0L) > 0).toList();
        if (present.isEmpty()) present = tokens;   // все отсутствуют → матч вернёт пусто, но не падаем

        double n = registryCount();
        return present.stream()
                .map(t -> new WeightedToken(t.token(),
                        t.weight() * Math.log((n + 1.0) / (df.getOrDefault(t.token(), 0L) + 1.0))))
                .toList();
    }

    private MatchConfidence confidenceOf(List<RegistryCandidateResponse> candidates) {
        if (candidates.isEmpty()) return MatchConfidence.CANNOT;
        Double top = candidates.get(0).getScore();
        if (top == null) return MatchConfidence.SHORTLIST;
        if (top >= CONFIDENT_MIN) return MatchConfidence.CONFIDENT;
        if (top >= SHORTLIST_MIN) return MatchConfidence.SHORTLIST;
        return MatchConfidence.CANNOT;
    }

    private CannotReason cannotReasonOf(List<RegistryCandidateResponse> candidates,
                                        TenderLot lot, boolean techSpecParsed) {
        if (confidenceOf(candidates) != MatchConfidence.CANNOT) return null;
        if (candidates.isEmpty()) return CannotReason.NO_CANDIDATES;
        if (techSpecParsed) return CannotReason.WEAK_MATCH;
        TechSpecStatus st = lot.getTechSpecStatus();
        if (st == TechSpecStatus.NO_FILE || st == TechSpecStatus.UNREADABLE || st == TechSpecStatus.ERROR) {
            return CannotReason.TECH_SPEC_FAILED;
        }
        return CannotReason.NEED_TECH_SPEC;
    }

    /** Размер реестра для IDF; стабилен на процесс (JSON-инициализатор наполняет один раз). */
    private volatile long registryCount = -1;
    private long registryCount() {
        long c = registryCount;
        if (c < 0) { c = registryRepository.count(); registryCount = c; }
        return c;
    }

    /** Кандидаты реестра по лоту (для LotSourcingService) — прежний контракт. */
    public List<RegistryCandidateResponse> candidatesForLot(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        return computeLotMatch(lot, limit).candidates();
    }

    /** Для панели «Подбор»: кандидаты + зона честности матча (confidence/cannotReason/techSpecParsed). */
    public LotRegistryMatchResponse matchForLotUi(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        LotMatch m = computeLotMatch(lot, limit);
        LotRegistryMatchResponse r = new LotRegistryMatchResponse();
        r.setCandidates(m.candidates());
        r.setConfidence(m.confidence());
        r.setCannotReason(m.cannotReason());
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
