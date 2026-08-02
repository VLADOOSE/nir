package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.dto.response.CannotReason;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.dto.response.RegistryCandidateRowV2;
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

    /*
     * Формула и пороги зон. КАЛИБРОВАНО 2026-08-02 на golden-наборе
     * (src/test/resources/registry/golden-lots.tsv — 71 размеченный лот, снимок реестра 14072).
     * Baseline до калибровки: recall@5 10/19, precision@1 9/19, корректность зоны 20/71 (28 %).
     * После: recall@5 13/19, precision@1 11/19, корректность зоны 58/71 (82 %),
     * «уверенных выдумок» 3 → 0. Метрики защищены гейтом RegistryMatchQualityTest.
     * Перекалибровывать при переимпорте реестра (веса IDF зависят от его размера).
     */

    /** β² для F<sub>β</sub>: β=1.5 — баланс сдвинут к recall. Единственный из трёх вариантов
     *  плана, который реально переставляет выдачу (recall@5 10 → 12/19); подробности и цифры
     *  отвергнутых вариантов — в javadoc {@code MedRegistryRepository.searchByTokensV2}. */
    static final double BETA_SQUARED    = 2.25;
    /** Сглаживание знаменателя precision_d: {@code nhit/(nden+2)} вместо {@code nhit/nden} —
     *  убирает взрывной приз за односложное название реестра (nsig=1 давало prec=1.0). */
    static final double PREC_SMOOTHING  = 2.0;
    /** Вес qualifier-бонуса (доля попавших токенов ТЗ). 0.3 → 0.5: +1 recall@5 и +1 precision@1;
     *  это и есть рычаг режима «класс верный, запись не та» («Стерилизатор» → Lowtem). */
    static final double QUALIFIER_BONUS = 0.5;
    /**
     * Отсечка по скору. НЕ порог похожести: для однотокенного запроса {@code score >= X}
     * эквивалентно «в названии записи не больше N значимых слов» и от близости совпадения не
     * зависит. На наборе значение в диапазоне 0.00…0.30 не меняет НИ ОДНОЙ метрики, поэтому
     * выбрано не по метрике, а по устойчивости: скоры однотокенных запросов лежат на
     * дискретной решётке {@code 3.25/(nsig+4.25)}, и узел nsig=12 приходится ровно на 0.200 —
     * порог, поставленный НА узел, отдаёт судьбу целой корзины записей округлению float8.
     * 0.19 лежит между узлами 12 (0.2000) и 13 (0.1884).
     */
    static final double SCORE_CUTOFF    = 0.19;
    /**
     * Порог показа процента и порог «слабого матча» совпадают НАМЕРЕННО, это не опечатка.
     * После введения гардов ниже границу между SHORTLIST и CANNOT проводит не скор, а
     * структура выдачи: низкий скор при нескольких равноправных записях — признак РОДОВОГО
     * лота, а не плохого совпадения, и прятать такой список нечестно. Скор же решает только
     * «показывать ли процент». Сетка 0.25…0.75 × шаг 0.05: 0.55/0.55 даёт максимум
     * корректности зоны (58/71); соседи стоят 1–2 кейса (0.60/0.55 → 57, 0.55/0.40 → 56).
     */
    static final double CONFIDENT_MIN   = 0.55;
    static final double SHORTLIST_MIN   = 0.55;

    /** Порог «запись полностью покрывает запрос» для подсчёта {@code rivals}. */
    static final double FULL_COVER_MIN  = 0.8;
    /**
     * Сколько равноправных записей ещё допускает CONFIDENT. Пять — потому что столько строк
     * и показывает панель: если полностью подходящих записей больше, чем помещается в выдачу,
     * называть первую ответом нельзя. Это главный гард — он один поднимает корректность зоны
     * с 42/71 до 58/71 и обнуляет главную ошибку baseline (20 GENERIC-лотов с процентом).
     * Плато 5…8 на наборе; за ним деградация (10 → 58, 15 → 56, без гарда → 43).
     */
    static final int    MAX_RIVALS      = 5;
    /**
     * При скольких равноправных записях показывать шорт-лист ВОПРЕКИ низкому скору. У родового
     * лота скор низок из-за длины названий реестра, а не из-за плохого совпадения (см.
     * SCORE_CUTOFF), поэтому без этого правила 13 GENERIC-лотов из 30 получали ПУСТОЙ ЭКРАН
     * при живых кандидатах. Метрика корректности зоны этого не видит (для неё CANNOT на
     * GENERIC — зачёт), и именно поэтому правило выбрано по диагностике «тихих зачётов»,
     * а не по метрике: 13 → 0 ценой 1 кейса зоны.
     */
    static final int    SHORTLIST_IF_RIVALS = 2;
    /**
     * Минимальная доля слов имени лота, которые вообще встречаются в реестре. Ниже — отбор шёл
     * по обрывку запроса, см. {@link CannotReason#QUERY_NOT_IN_REGISTRY}. Плато 0.6…1.0 на
     * наборе; взято левое, наименее вмешивающееся значение.
     */
    static final double MIN_QUERY_COVER = 0.6;

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

        WeightedQuery wq = withIdfWeights(query.identity());
        String toks = wq.tokens().stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        String wgts = wq.tokens().stream()
                .map(t -> String.format(Locale.ROOT, "%.3f", t.weight()))
                .collect(Collectors.joining("|"));
        String quals = String.join("|", query.qualifier());

        List<RegistryCandidateRowV2> rows = registryRepository.searchByTokensV2(
                toks, wgts, quals, BETA_SQUARED, PREC_SMOOTHING, QUALIFIER_BONUS,
                FULL_COVER_MIN, SCORE_CUTOFF, limit);
        List<RegistryCandidateResponse> candidates = rows.stream().map(this::toCandidate).toList();
        // rivals одинаков во всех строках (окно по пулу) — берём из любой
        int rivals = rows.isEmpty() || rows.get(0).getRivals() == null ? 0 : rows.get(0).getRivals();

        MatchConfidence zone = confidenceOf(candidates, rivals, wq.cover());
        return new LotMatch(candidates, zone,
                cannotReasonOf(zone, candidates, wq.cover(), lot, query.techSpecParsed()),
                query.techSpecParsed());
    }

    /** Взвешенный запрос + доля слов имени, которые вообще есть в реестре ({@code cover}). */
    private record WeightedQuery(List<WeightedToken> tokens, double cover) {}

    /**
     * Финальный вес = фактор источника × IDF ln((N+1)/(df+1)); токены с df=0 выкидываем (§8).
     *
     * <p>Возвращает ещё и {@code cover} — какая доля исходных identity-токенов пережила выброс.
     * В двухстадийной схеме выброс df=0 решает не вес, а ОТБОР, поэтому «сколько слов лота
     * вообще есть в реестре» — самостоятельный вход зоны честности, а не деталь взвешивания.
     */
    private WeightedQuery withIdfWeights(List<WeightedToken> tokens) {
        String allToks = tokens.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        Map<String, Long> df = registryRepository.tokenDocFreq(allToks).stream()
                .collect(Collectors.toMap(TokenDfRow::getTok, TokenDfRow::getDf, (a, b) -> a));
        List<WeightedToken> present = tokens.stream()
                .filter(t -> df.getOrDefault(t.token(), 0L) > 0).toList();
        double cover = tokens.isEmpty() ? 1.0 : (double) present.size() / tokens.size();
        if (present.isEmpty()) present = tokens;   // все отсутствуют → матч вернёт пусто, но не падаем

        double n = registryCount();
        return new WeightedQuery(present.stream()
                .map(t -> new WeightedToken(t.token(),
                        t.weight() * Math.log((n + 1.0) / (df.getOrDefault(t.token(), 0L) + 1.0))))
                .toList(), cover);
    }

    /**
     * Зона честности. Три входа, а не один: скор топ-кандидата отвечает только на вопрос
     * «показывать ли процент», а «есть ли вообще что показывать» решают структурные признаки —
     * сколько равноправных ответов у лота ({@code rivals}) и по всему ли имени шёл отбор
     * ({@code queryCover}). Прежняя версия читала ТОЛЬКО скор и потому объявляла уверенность
     * на «Перчатках» (147 равноправных записей) и на обрывке запроса.
     */
    private MatchConfidence confidenceOf(List<RegistryCandidateResponse> candidates,
                                         int rivals, double queryCover) {
        if (candidates.isEmpty()) return MatchConfidence.CANNOT;
        if (queryCover < MIN_QUERY_COVER) return MatchConfidence.CANNOT;
        Double top = candidates.get(0).getScore();
        if (top == null) return MatchConfidence.SHORTLIST;
        if (top >= CONFIDENT_MIN && rivals <= MAX_RIVALS) return MatchConfidence.CONFIDENT;
        if (top >= SHORTLIST_MIN || rivals >= SHORTLIST_IF_RIVALS) return MatchConfidence.SHORTLIST;
        return MatchConfidence.CANNOT;
    }

    private CannotReason cannotReasonOf(MatchConfidence zone,
                                        List<RegistryCandidateResponse> candidates,
                                        double queryCover, TenderLot lot, boolean techSpecParsed) {
        if (zone != MatchConfidence.CANNOT) return null;
        if (candidates.isEmpty()) return CannotReason.NO_CANDIDATES;
        // проверяется РАНЬШЕ ТЗ: если слов лота нет в реестре, разбор техспеки этого не исправит —
        // предлагать «разберите ТЗ» значит отправить оператора за бесполезной работой
        if (queryCover < MIN_QUERY_COVER) return CannotReason.QUERY_NOT_IN_REGISTRY;
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
