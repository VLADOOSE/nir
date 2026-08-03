package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Качество реестр-подбора на размеченном наборе реальных лотов
 * ({@code src/test/resources/registry/golden-lots.tsv} — 71 лот nirdb, метки сверены поиском
 * по живому реестру НЦЭЛС ~14k).
 *
 * <p><b>Это гейт качества подбора</b> (с Task 6; до неё — только измерительный инструмент).
 * Метрики печатаются в лог, а внизу теста стоят жёсткие отсечки — НЕ на «корректности зоны»,
 * у которой есть пол 52/71, берущийся чистым молчанием, а на её составляющих: recall@5,
 * precision@1, ноль уверенных выдумок, ноль генериков с процентом, ноль спрятанных верных
 * ответов, РУ уверенно-и-верно ≥ 8. Обоснование — у самих ассертов. Плюс гейт целостности
 * самого набора (см. {@link #load()}): пустой или побитый файл тест не пропустит, иначе
 * «улучшение» ранжирования доказывалось бы на пустом месте.
 *
 * <p>Три метрики брифа:
 * <ul>
 *   <li><b>recall@5</b> — размеченный РУ попал в топ-5 (считается только по РУ-кейсам);</li>
 *   <li><b>precision@1</b> — размеченный РУ оказался первым;</li>
 *   <li><b>корректность зоны</b> — система сказала правду о своей уверенности:
 *       NONE → {@code CANNOT}; GENERIC → НЕ {@code CONFIDENT}; РУ → {@code CONFIDENT} и в топ-5.</li>
 * </ul>
 *
 * <p><b>Мульти-ключ.</b> Ожидание РУ-кейса — это {@code РУ1|РУ2|…}: у части лотов верный
 * ответ не одна запись, а семейство равноправных (все ангиографические системы реестра,
 * все плоскопанельные детекторы 43*43 см). Засчитывается ЛЮБОЙ ключ семейства — иначе
 * метрика пометила бы регрессией правку Task 6, поднявшую в топ соседнюю верную запись.
 *
 * <p>Дополнительно печатается разбивка «ожидание × фактическая зона» и два диагностических
 * счётчика, которых нет в трёх метриках, но которые нужны Task 6, чтобы не чинить вслепую:
 * <ul>
 *   <li><i>тихие зачёты</i> — GENERIC/NONE-кейс, попавший в {@code CANNOT} с НЕПУСТЫМ списком:
 *       по метрике зачёт, а оператору верные кандидаты не показаны;</li>
 *   <li><i>уверенная выдумка</i> — {@code CONFIDENT} на кейсе, размеченном NONE: худший режим
 *       отказа, ради устранения которого зоны и вводились.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class RegistryMatchQualityTest {

    /** {@code keys} пуст у NONE/GENERIC; у РУ-кейса — один или несколько равноправных РУ. */
    private record Case(String name, String spec, String expectation, List<String> keys) {}

    private static final String NONE = "NONE";
    private static final String GENERIC = "GENERIC";

    @Autowired TenderRepository tenderRepository;
    @Autowired RegistryMatchService service;
    @Autowired MedRegistryRepository registryRepository;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    /**
     * Загрузка с проверкой самого набора. Без неё «зелёный» тест ничего не значит: файл,
     * побитый до одной уцелевшей строки, всё так же напечатал бы baseline — просто бессмысленный.
     * Поэтому три отказа ГРОМКИЕ, а не «continue»:
     * <ol>
     *   <li>разобрано не столько кейсов, сколько объявлено строкой {@code # КЕЙСОВ: N}
     *       в шапке файла. Считать строки самого файла для этого НЕДОСТАТОЧНО: у усечённого
     *       файла счётчик усечётся вместе с ним и сойдётся сам с собой — поймать потерю
     *       может только число, объявленное ВНЕ данных;</li>
     *   <li>метка не из словаря — опечатка вроде {@code None} или кириллической «С» в
     *       {@code GENERIC} превратила бы кейс в РУ-ключ, который не найдётся никогда,
     *       то есть в вечный промах recall, выглядящий как дефект ранжирования;</li>
     *   <li>РУ-ключа нет в реестре — та же вечная непобедимая ошибка, только от опечатки
     *       в номере или от переимпорта реестра, где запись исчезла.</li>
     * </ol>
     */
    private List<Case> load() throws Exception {
        List<Case> cases = new ArrayList<>();
        int dataLines = 0;
        Integer declared = null;
        List<String> bad = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("registry/golden-lots.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("# КЕЙСОВ:")) {
                    declared = Integer.parseInt(line.substring("# КЕЙСОВ:".length()).trim());
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) continue;
                dataLines++;
                String[] p = line.split("\t", -1);
                if (p.length != 3 || p[0].isBlank() || p[2].isBlank()) {
                    bad.add("строка не из трёх непустых полей: " + line);
                    continue;
                }
                String exp = p[2].trim();
                List<String> keys = NONE.equals(exp) || GENERIC.equals(exp)
                        ? List.of()
                        : java.util.Arrays.stream(exp.split("\\|")).map(String::trim).filter(s -> !s.isBlank()).toList();
                if (!NONE.equals(exp) && !GENERIC.equals(exp) && keys.isEmpty()) {
                    bad.add("пустой набор РУ-ключей: " + p[0]);
                    continue;
                }
                cases.add(new Case(p[0], p[1].isBlank() ? null : p[1], exp, keys));
            }
        }
        assertThat(bad).as("набор разобран без потерь").isEmpty();
        assertThat(cases).as("строк данных разобрано столько же, сколько в файле").hasSize(dataLines);
        assertThat(declared).as("шапка объявляет число кейсов строкой «# КЕЙСОВ: N»").isNotNull();
        assertThat(cases).as("кейсов столько, сколько объявлено в шапке (гард от усечения файла)")
                .hasSize(declared);

        List<String> unknown = cases.stream().flatMap(c -> c.keys().stream()).distinct()
                .filter(k -> registryRepository.findByRegNumber(k).isEmpty()).toList();
        assertThat(unknown).as("все РУ-ключи набора существуют в реестре (иначе кейс — вечный промах)")
                .isEmpty();
        return cases;
    }

    /** Проценты рядом с дробью — чтобы сравнение с baseline Task 6 было механическим. */
    private static String pct(int hits, int total) {
        return total == 0 ? "н/д" : String.format(Locale.ROOT, "%.0f %%", 100.0 * hits / total);
    }

    private LotRegistryMatchResponse match(Case c) {
        Tender t = new Tender();
        t.setTenderNumber("GOLD-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(c.name());
        l.setRequiredSpec(c.spec());
        t.getLots().add(l);
        tenderRepository.save(t);
        return service.matchForLotUi(t.getLots().get(0).getId(), 5);
    }

    @Test
    void reportsQualityMetrics() throws Exception {
        List<Case> cases = load();
        assertThat(cases).as("golden-набор не пуст").isNotEmpty();

        int recallHits = 0, recallTotal = 0;
        int precisionHits = 0, precisionTotal = 0;
        int zoneHits = 0, silentPass = 0, confidentFiction = 0;
        int hiddenRight = 0, hiddenRank1 = 0, genericConfident = 0, ruConfidentCorrect = 0;
        Map<MatchConfidence, int[]> byExpectation = new EnumMap<>(MatchConfidence.class);
        for (MatchConfidence z : MatchConfidence.values()) byExpectation.put(z, new int[3]); // NONE|GENERIC|РУ
        List<String> failures = new ArrayList<>();

        for (Case c : cases) {
            LotRegistryMatchResponse r = match(c);
            List<RegistryCandidateResponse> cands = r.getCandidates();
            String top = cands.isEmpty() ? "—" : cands.get(0).getRegNumber();

            switch (c.keys().isEmpty() ? c.expectation() : "РУ") {
                case NONE -> {
                    byExpectation.get(r.getConfidence())[0]++;
                    if (r.getConfidence() == MatchConfidence.CANNOT) {
                        zoneHits++;
                        if (!cands.isEmpty()) silentPass++;
                    } else {
                        if (r.getConfidence() == MatchConfidence.CONFIDENT) confidentFiction++;
                        failures.add("должен был сказать «не могу»: " + c.name()
                                + " → " + r.getConfidence() + " (топ " + top + ")");
                    }
                }
                case GENERIC -> {
                    byExpectation.get(r.getConfidence())[1]++;
                    if (r.getConfidence() != MatchConfidence.CONFIDENT) {
                        zoneHits++;
                        if (r.getConfidence() == MatchConfidence.CANNOT && !cands.isEmpty()) silentPass++;
                    } else {
                        genericConfident++;
                        failures.add("уверен там, где генерик: " + c.name() + " (топ " + top + ")");
                    }
                }
                default -> {
                    byExpectation.get(r.getConfidence())[2]++;
                    recallTotal++;
                    precisionTotal++;
                    // мульти-ключ: семейство равноправных записей, любая из них — верный ответ
                    boolean inTop5 = cands.stream()
                            .anyMatch(x -> c.keys().stream().anyMatch(k -> k.equalsIgnoreCase(x.getRegNumber())));
                    if (inTop5) recallHits++;
                    else failures.add("нет в топ-5: " + c.name() + " (ждали "
                            + (c.keys().size() == 1 ? c.keys().get(0)
                                                    : "любой из " + c.keys().size() + ": " + c.keys().get(0) + "…")
                            + ", топ " + top + ")");
                    boolean atRank1 = !cands.isEmpty()
                            && c.keys().stream().anyMatch(k -> k.equalsIgnoreCase(cands.get(0).getRegNumber()));
                    if (atRank1) precisionHits++;
                    // Худший режим отказа ПОСЛЕ уверенной выдумки: верный ответ найден и лежит
                    // в выдаче, а панель показывает пустой экран. Метрика зоны его не видит —
                    // для неё РУ без CONFIDENT просто «не зачёт», без разницы, показан список
                    // или нет. Этой слепотой в первой редакции Task 6 и была скрыта дыра C1.
                    if (r.getConfidence() == MatchConfidence.CANNOT) {
                        if (inTop5) hiddenRight++;
                        if (atRank1) hiddenRank1++;
                    }
                    // ⚠️ И zoneHits, И гейт считаются ТОЛЬКО по «уверенно И верно». Раньше гейт
                    // стоял на сырой зонной раскладке byExpectation[CONFIDENT][2], которая
                    // растёт от одной лишь уверенности: при MAX_RIVALS=8 «Трубка
                    // эндотрахеальная» становилась CONFIDENT с чужой записью в топе (ни один
                    // из четырёх размеченных эндобронхиальных ключей не в топ-5), счётчик
                    // поднимался до 9 и читался как улучшение, а все шесть отсечек проходили.
                    // Это третий режим отказа плана («класс верный, запись не та») с зелёным
                    // гейтом. Уверенная выдумка охранялась на NONE и GENERIC, но не на РУ —
                    // здесь эта асимметрия и закрывается.
                    if (r.getConfidence() == MatchConfidence.CONFIDENT && inTop5) {
                        zoneHits++;
                        ruConfidentCorrect++;
                    }
                }
            }
        }

        System.out.printf("%n=== КАЧЕСТВО РЕЕСТР-ПОДБОРА (%d кейсов) ===%n", cases.size());
        System.out.printf("recall@5:          %d/%d (%s)%n", recallHits, recallTotal, pct(recallHits, recallTotal));
        System.out.printf("precision@1:       %d/%d (%s)%n", precisionHits, precisionTotal,
                pct(precisionHits, precisionTotal));
        System.out.printf("корректность зоны: %d/%d (%s)%n", zoneHits, cases.size(), pct(zoneHits, cases.size()));
        System.out.printf("%nожидание \\ зона   CONFIDENT  SHORTLIST  CANNOT%n");
        System.out.printf("NONE               %6d %10d %7d%n",
                byExpectation.get(MatchConfidence.CONFIDENT)[0],
                byExpectation.get(MatchConfidence.SHORTLIST)[0],
                byExpectation.get(MatchConfidence.CANNOT)[0]);
        System.out.printf("GENERIC            %6d %10d %7d%n",
                byExpectation.get(MatchConfidence.CONFIDENT)[1],
                byExpectation.get(MatchConfidence.SHORTLIST)[1],
                byExpectation.get(MatchConfidence.CANNOT)[1]);
        System.out.printf("РУ                 %6d %10d %7d%n",
                byExpectation.get(MatchConfidence.CONFIDENT)[2],
                byExpectation.get(MatchConfidence.SHORTLIST)[2],
                byExpectation.get(MatchConfidence.CANNOT)[2]);
        System.out.printf("%nтихих зачётов (CANNOT при непустой выдаче): %d%n", silentPass);
        System.out.printf("уверенной выдумки (CONFIDENT на NONE):      %d%n", confidentFiction);
        System.out.printf("генерик с ложным процентом:                 %d%n", genericConfident);
        System.out.printf("СПРЯТАНО верных (РУ в топ-5, зона CANNOT):  %d (из них ранг-1: %d)%n",
                hiddenRight, hiddenRank1);
        System.out.printf("РУ уверенно и верно (информативная часть):   %d/%d  "
                + "(в зоне CONFIDENT всего %d — разница = уверенно, но НЕ то)%n",
                ruConfidentCorrect, recallTotal, byExpectation.get(MatchConfidence.CONFIDENT)[2]);
        System.out.printf("пол метрики «всегда CANNOT»:                %d/%d%n",
                cases.stream().filter(c -> c.keys().isEmpty()).count(), cases.size());
        failures.forEach(f -> System.out.println("  ✗ " + f));

        // ================== ЖЁСТКИЙ ГЕЙТ (Task 6, пересобран в fix-round 1) ==================
        //
        // ⚠️ ПОЧЕМУ ГЕЙТ НЕ НА «КОРРЕКТНОСТИ ЗОНЫ». Этой метрикой нельзя защищать качество:
        // у неё есть ПОЛ, который берётся молчанием. Политика «всегда CANNOT», не делающая
        // вообще ни одного запроса к реестру, набирает 52/71 (73 %): NONE зачёт по определению,
        // GENERIC зачёт как «не CONFIDENT». То есть чем больше система прячет, тем лучше цифра,
        // а требование «≥ 80 %» из брифа поощряло бы прятать. Ровно в эту ловушку первая
        // редакция Task 6 и попала: 58/71 (82 %) были куплены тем, что три РУ-лота с ВЕРНЫМ
        // ответом на первом месте показывали пустой экран.
        //
        // Поэтому гейт стоит на СОСТАВЛЯЮЩИХ, каждая из которых молчанием не накручивается.
        // Вместе они покрывают все три компонента корректности зоны, но в той полярности,
        // в какой ошибка вредна.
        //
        // ⚠️ ЗАПАС У ОТСЕЧЕК 1–2 И 5 РОВНО НУЛЕВОЙ (13, 11, 8) — это храповик, он намеренный.
        // Но переимпорт реестра сдвигает веса IDF и потому МОЖЕТ уронить сборку без единой
        // правки кода (об этом же говорит «Перекалибровывать при переимпорте» в
        // RegistryMatchService). Покраснело после переимпорта — сперва прочитайте шапку
        // src/test/resources/registry/golden-lots.tsv: там записан снимок реестра, на котором
        // сняты эти числа, и порядок перемера baseline. Это перекалибровка, а не регрессия.
        //
        // Замеры 2026-08-02 (снимок реестра 14072, тай-брейк по reg_number включён):
        // recall@5 13/19 · precision@1 11/19 · зона 49/71 (69 %) · РУ уверенно и верно 8/19 ·
        // выдумок 0 · генерик с процентом 0 · спрятано верных 0.

        // 1. Ранжирование. Baseline до Task 6: 10/19 и 9/19.
        assertThat(recallHits).as("recall@5 не ниже достигнутого (baseline 10/19)")
                .isGreaterThanOrEqualTo(13);
        assertThat(precisionHits).as("precision@1 не ниже достигнутого (baseline 9/19)")
                .isGreaterThanOrEqualTo(11);

        // 2. Худший режим отказа: уверенно названа запись там, где подходящей в реестре НЕТ.
        assertThat(confidentFiction)
                .as("уверенных выдумок (CONFIDENT на NONE) быть не должно — было 3 до Task 6")
                .isZero();

        // 3. Главная ошибка baseline: процент на родовом лоте, где записей сотни.
        assertThat(genericConfident)
                .as("генериков с ложным процентом быть не должно — было 20 из 30 до Task 6")
                .isZero();

        // 4. Дефект C1, который метрика зоны не видит и который она же и поощряла:
        //    верный ответ найден, лежит в выдаче — и спрятан за пустым экраном.
        assertThat(hiddenRight)
                .as("ни один найденный верный ответ не должен быть спрятан зоной CANNOT")
                .isZero();

        // 5. Информативная часть корректности зоны — та единственная, которую политика
        //    молчания набрать не может (у неё здесь ровно 0). Считается по «CONFIDENT И
        //    ключ в топ-5», а не по одной лишь зоне: иначе гейт зеленел бы от уверенности
        //    с ЧУЖОЙ записью в топе (см. комментарий у ruConfidentCorrect выше).
        assertThat(ruConfidentCorrect)
                .as("РУ-лотов, названных уверенно И ВЕРНО, не меньше достигнутого")
                .isGreaterThanOrEqualTo(8);

        // 6. Грубый предохранитель от катастрофического дрейфа. НЕ показатель качества:
        //    сравнивать его следует с полом 52/71, а не со 100 %.
        assertThat(zoneHits).as("корректность зоны не обвалилась (пол политики молчания — 52/71)")
                .isGreaterThanOrEqualTo(45);
    }
}
