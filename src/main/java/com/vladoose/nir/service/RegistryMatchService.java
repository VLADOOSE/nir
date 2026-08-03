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
     * (src/test/resources/registry/golden-lots.tsv — 71 размеченный лот, снимок реестра 14072),
     * значения перемерены СОВМЕСТНО в финальной рабочей точке после fix-round 1 — не покоординатно.
     *
     *   было (baseline)  →  стало
     *   recall@5              10/19  →  13/19
     *   precision@1            9/19  →  11/19
     *   уверенных выдумок         3  →  0
     *   генериков с процентом    20  →  0   (из 30)
     *   спрятанных верных         0  →  0   (первая редакция Task 6 ломала это на 3 — см. SHORTLIST_MIN)
     *   РУ уверенно и верно     n/д  →  8/19
     *   корректность зоны     20/71  →  49/71
     *
     * ⚠️ «Корректность зоны» УПАЛА относительно первой редакции (58/71) и это НЕ регресс:
     * у метрики есть пол 52/71, который берётся политикой «всегда CANNOT», то есть она поощряет
     * молчание. 58/71 были куплены сокрытием трёх верных ответов. Гейт поэтому стоит на
     * составляющих (RegistryMatchQualityTest), а не на этой сумме.
     *
     * Перекалибровывать при переимпорте реестра (веса IDF зависят от его размера).
     */

    /**
     * β² для F<sub>β</sub>: β=1.5 — баланс сдвинут к recall. Замер СОВМЕСТНЫЙ, в рабочей точке
     * (CONF 0.55 / SHORT 0.30 / maxRivals 5), потому что покоординатный спуск здесь врёт:
     * <pre>
     *   β=1.0 k=0 b=0.3 (baseline)  зона 46  r@5 10  p@1  9  выдумок 0
     *   β=1.5 k=2 b=0.5 (принято)   зона 49  r@5 13  p@1 11  выдумок 0
     *   β=2.0 k=2 b=0.5             зона 48  r@5 13  p@1 10  выдумок 1
     *   β=2.5 k=2 b=0.5             зона 51  r@5 13  p@1 11  выдумок 1
     *   β=3.0 k=2 b=0.5             зона 49  r@5 13  p@1 11  выдумок 2 + 2 генерика с процентом
     * </pre>
     * β=2.5 даёт зону выше, но ЦЕНОЙ уверенной выдумки — худшего режима отказа, ради устранения
     * которого зоны и вводились. Берём β=1.5: единственная точка с нулём выдумок при максимуме
     * ранжирующих метрик. Цифры отвергнутых вариантов формулы — в javadoc
     * {@code MedRegistryRepository.searchByTokensV2}.
     */
    static final double BETA_SQUARED    = 2.25;
    /** Сглаживание знаменателя precision_d: {@code nhit/(nden+2)} вместо {@code nhit/nden} —
     *  убирает взрывной приз за односложное название реестра (nsig=1 давало prec=1.0).
     *  В одиночку ранжирование НЕ двигает (монотонно по nden → порядок сохраняет). */
    static final double PREC_SMOOTHING  = 2.0;
    /**
     * Вес qualifier-бонуса (доля попавших токенов ТЗ). 0.3 → 0.5: в рабочей точке
     * b=0.3 даёт r@5 12 / p@1 10 / зона 46, b=0.4 — 12/10/48, b=0.5 — 13/11/49.
     *
     * <p><b>Почему НЕ выше, хотя b=1.0 даёт r@5 14.</b> Требование брифа (факт 3) — перемерить
     * смещение бонуса по длине названия — выполнено, и смещение подтвердилось. Кандидаты
     * «перчатки» с 4-токенным qualifier, разбивка по nsig (значимых слов в названии записи):
     * <pre>
     *   nsig    записей   ср. hit   ср. F-beta   бонус/F-beta
     *   1–4        1       0.250      0.394         0.32
     *   5–8       64       0.453      0.286         0.79
     *   9–12      76       0.612      0.228         1.34
     *   13–16      5       0.500      0.181         1.38
     * </pre>
     * Доля попаданий РАСТЁТ с длиной названия (0.250 → 0.612) просто потому, что
     * {@code word_similarity(токен, name)} ищет по ВСЕМУ названию: чем оно длиннее, тем больше
     * шансов у любого токена куда-нибудь попасть. Уже при 0.5 бонус на длинных записях
     * ПРЕВЫШАЕТ весь член F-beta (1.34×), то есть съедает нормировку по длине — ровно ту
     * работу, ради которой precision_d и вводился. При 1.0 отношение удвоится, и +1 recall был
     * бы куплен усилением измеренного смещения. 0.5 — наименьшее значение, дающее плато
     * ранжирующих метрик.
     */
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
    /** Порог показа процента. Сетка 0.45…0.75 шагом 0.05 в рабочей точке: 0.55 — максимум
     *  корректности зоны (49/71) при НУЛЕВОЙ уверенной выдумке; 0.60 → 48, 0.65 → 46, а
     *  0.50 и 0.45 начинают выдумывать (1 и 2 выдумки соответственно). */
    static final double CONFIDENT_MIN   = 0.55;
    /**
     * Порог «есть что показать» при ОДНОМ правдоподобном ответе.
     *
     * <p><b>0.55 → 0.30 (fix-round 1, ревью C1). Это не тюнинг, а закрытие дыры, которая
     * прятала ВЕРНЫЕ уникальные ответы.</b> Когда пороги совпадали (0.55/0.55), шорт-лист
     * спасал только правило {@code rivals >= 2}, и лот с полностью покрытым запросом, РОВНО
     * ОДНИМ правдоподобным кандидатом и скором в [0.19, 0.55) уходил в CANNOT — то есть в
     * пустой экран. На golden-наборе так прятались 3 РУ-лота, у ВСЕХ ТРЁХ верная запись
     * стояла ПЕРВОЙ: «Нефроскоп» (0.3824), «Стетофонендоскоп» (0.3939, единственный кандидат),
     * «Расходные материалы … HMA-55» (0.4340). На 250 случайных импортных лотах в дыру
     * попадали 46 (18 %), включая очевидно верные («Напальчник» → «Напальчники медицинские
     * резиновые» 0.435). Молчать тем увереннее, чем однозначнее ответ, — прямая инверсия
     * цели фичи.
     *
     * <p>Значение выбрано так: 0.35 тоже закрывает дыру на наборе, но лежит ВПЛОТНУЮ к узлу
     * решётки {@code 3.25/(nsig+4.25)} для nsig=5 (0.35135) — тот же дефект, из-за которого
     * {@link #SCORE_CUTOFF} ушёл с 0.20. 0.30 лежит между узлами 0.28889 (nsig=7) и 0.31707
     * (nsig=6) и вдобавок покрывает бо́льшую часть корпусной дыры.
     *
     * <p>⚠️ Цена честно измерена и принята: корректность зоны 58/71 → 49/71. Метрика ПАДАЕТ,
     * потому что структурно поощряет молчание (пол политики «всегда CANNOT» — 52/71).
     * Показывать шорт-лист там, где ответа нет, — мягкий грех (оператор видит список и
     * отклоняет его); прятать найденный верный ответ — тяжёлый.
     *
     * <p><b>Порог ЖИВОЙ — проверено прямым замером, а не рассуждением.</b> При 0.55 он был
     * ПРОВЕРЯЕМО МЁРТВ: значения 0.55 и 99 давали идентичные метрики (зона 58/71, спрятано 3),
     * потому что при {@code top >= 0.55} условие {@code rivals > 5} уже влечёт
     * {@code rivals >= 2} и ветка rivals срабатывала раньше. При 0.30 — зона 49/71, спрятано 0,
     * то есть порог реально решает судьбу лотов. Поставите его снова равным
     * {@link #CONFIDENT_MIN} — вернёте дыру C1.
     */
    static final double SHORTLIST_MIN   = 0.30;

    /** Порог «запись полностью покрывает запрос» для подсчёта {@code rivals}. */
    static final double FULL_COVER_MIN  = 0.8;
    /**
     * Сколько равноправных записей ещё допускает CONFIDENT. Пять — потому что столько строк
     * и показывает панель: если полностью подходящих записей больше, чем помещается в выдачу,
     * называть первую ответом нельзя.
     *
     * <p>Это главный гард. Замер в рабочей точке: 1 → 46, 2 → 47, 3 → 48, <b>5 → 49, 6 → 49,
     * 8 → 49</b>, 10 → 48, 15 → 46, 25 → 45, без гарда → <b>33</b>. Без него генериков с
     * ложным процентом снова 17 из 30 — то есть именно он снимает главную ошибку baseline.
     * Плато 5…8; взято 5 как объяснимое числом строк панели.
     */
    static final int    MAX_RIVALS      = 5;
    /**
     * При скольких равноправных записях показывать шорт-лист ВОПРЕКИ низкому скору: у родового
     * лота скор низок из-за длины названий реестра, а не из-за плохого совпадения.
     *
     * <p>⚠️ <b>Честно: на текущем наборе этот гард ИНЕРТЕН.</b> После фикса C1
     * ({@link #SHORTLIST_MIN} = 0.30) значения 2, 3, 5 и даже 999 дают идентичные метрики —
     * все 30 родовых лотов набора имеют топ ≥ 0.30 и спасаются порогом, не этим правилом.
     * До C1 он был нужен (спасал 13 родовых лотов из 30 от пустого экрана), и в javadoc
     * первой редакции это было записано как его действующее обоснование — сейчас это уже
     * не так.
     *
     * <p>Оставлен намеренно: он покрывает случай, которого в наборе нет, — родовой лот, у
     * которого ВСЕ подходящие записи реестра длинноимённые и потому дают скор ниже 0.30.
     * Правило семантически верное («много равноправных ⇒ это родовой лот, а не слабый матч»),
     * стоит дёшево и не может навредить. Но при следующей калибровке его надо либо подтвердить
     * корпусным примером, либо удалить — не оставлять как есть на третий круг.
     */
    static final int    SHORTLIST_IF_RIVALS = 2;
    /**
     * Минимальная доля слов имени лота, которые вообще встречаются в реестре. Ниже — отбор шёл
     * по обрывку запроса, см. {@link CannotReason#QUERY_NOT_IN_REGISTRY}.
     *
     * <p>Замер в рабочей точке: 0.0 → зона 44, 0.4 → 46, 0.5 → 47, <b>0.6 → 49</b>, 0.7 → 50,
     * 0.8 → 50, 1.0 → 50. Формально максимум правее, но начиная с 0.7 гард НАЧИНАЕТ ПРЯТАТЬ
     * верный ответ (спрятанных верных 0 → 1): лоту хватает и неполного покрытия имени.
     * 0.6 — наибольшее значение, при котором ничего верного не теряется.
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
        boolean specParsed = specParsed(lot, query.techSpecParsed());

        // Бренд задан оператором (частные заявки West-Med) — прежний бренд-путь, он не диагностирован как проблемный
        if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
            List<RegistryCandidateResponse> byBrand =
                    findCandidates(lot.getEquipName(), lot.getManufact(), limit);
            return new LotMatch(byBrand,
                    byBrand.isEmpty() ? MatchConfidence.CANNOT : MatchConfidence.CONFIDENT,
                    byBrand.isEmpty() ? CannotReason.NO_CANDIDATES : null,
                    specParsed);
        }

        if (query.identity().isEmpty()) {
            return new LotMatch(List.of(), MatchConfidence.CANNOT,
                    CannotReason.NO_CANDIDATES, specParsed);
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
                cannotReasonOf(zone, candidates, wq.cover(), lot, specParsed),
                specParsed);
    }

    /**
     * «Техспека этого лота разобрана» — ПРОДУКТОВЫЙ признак, и он НЕ тот же самый вопрос,
     * на который отвечает {@link LotQuery#techSpecParsed()}.
     *
     * <p>{@code LotQuery.techSpecParsed()} значит ровно «нашёлся якорь
     * «характеристики закупаемых товаров», и закупочную шапку удалось отрезать» — это годится
     * для выбора текста qualifier’а, но как признак «ТЗ есть» это <b>артефакт шаблона
     * goszakup</b>. Замер на живой nirdb (лоты со {@code tech_spec_status='OK'}, якорь ищется
     * так же, как в {@code characteristics()} — по нормализованным пробелам):
     * <pre>
     *   GOSZAKUP + legacy : 11 из 11 с якорем
     *   SK_PHARMACY       :  0 из 39 с якорем   ← у каждого разобранное ТЗ ~9–24 тыс. символов
     * </pre>
     * СК-Фармация пишет свой заголовок («Техническая спецификация Лот … № п/п Критерии
     * Описание»), якоря goszakup там нет и не будет.
     *
     * <p>Без этой поправки все 39 SK-лотов проваливались в {@code cannotReasonOf} мимо
     * {@code WEAK_MATCH} к {@code NEED_TECH_SPEC}, и панель советовала «разберите
     * техспецификацию» лоту, ТЗ которого уже разобрано фоновой очередью: оператор жмёт «ТЗ»,
     * тот же PDF скачивается заново, тот же текст пишется заново, совет не меняется. Это
     * прямо нарушало принцип, записанный тремя строками выше самого {@code cannotReasonOf},
     * — не отправлять оператора за бесполезной работой. Побочно {@code WEAK_MATCH} был
     * НЕДОСТИЖИМ для той единственной площадки, которую очередь реально обслуживает на проде
     * (goszakup там блокирует IP, см. §16 CLAUDE.md), — живой свип показывал WEAK_MATCH = 0.
     *
     * <p>Поэтому персистентный {@code TechSpecStatus.OK} — равноправный источник признака.
     * Он же уходит в DTO ({@code LotRegistryMatchResponse.techSpecParsed}) и гасит ту же
     * подсказку в SHORTLIST-баннере панели: обе поверхности должны отвечать на один вопрос
     * одинаково, иначе разъедутся при следующей правке.
     */
    private static boolean specParsed(TenderLot lot, boolean anchorFound) {
        return anchorFound || lot.getTechSpecStatus() == TechSpecStatus.OK;
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

    /** {@code specParsed} — ПРОДУКТОВЫЙ признак «ТЗ разобрано» (якорь ИЛИ статус OK), см. {@link #specParsed}. */
    private CannotReason cannotReasonOf(MatchConfidence zone,
                                        List<RegistryCandidateResponse> candidates,
                                        double queryCover, TenderLot lot, boolean specParsed) {
        if (zone != MatchConfidence.CANNOT) return null;
        if (candidates.isEmpty()) return CannotReason.NO_CANDIDATES;
        // проверяется РАНЬШЕ ТЗ: если слов лота нет в реестре, разбор техспеки этого не исправит —
        // предлагать «разберите ТЗ» значит отправить оператора за бесполезной работой
        if (queryCover < MIN_QUERY_COVER) return CannotReason.QUERY_NOT_IN_REGISTRY;
        if (specParsed) return CannotReason.WEAK_MATCH;
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
