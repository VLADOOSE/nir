package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Качество реестр-подбора на размеченном наборе реальных лотов
 * ({@code src/test/resources/registry/golden-lots.tsv} — 71 лот nirdb, метки сверены поиском
 * по живому реестру НЦЭЛС ~14k).
 *
 * <p><b>Это измерительный инструмент, а не гейт.</b> Метрики печатаются в лог; жёсткая отсечка
 * по корректности зоны ставится в Task 6, когда будет что защищать. Здесь проверяется только,
 * что набор не пуст — иначе «улучшение» ранжирования доказывалось бы на пустом месте.
 *
 * <p>Три метрики брифа:
 * <ul>
 *   <li><b>recall@5</b> — размеченный РУ попал в топ-5 (считается только по РУ-кейсам);</li>
 *   <li><b>precision@1</b> — размеченный РУ оказался первым;</li>
 *   <li><b>корректность зоны</b> — система сказала правду о своей уверенности:
 *       NONE → {@code CANNOT}; GENERIC → НЕ {@code CONFIDENT}; РУ → {@code CONFIDENT} и в топ-5.</li>
 * </ul>
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

    private record Case(String name, String spec, String expectation) {}

    @Autowired TenderRepository tenderRepository;
    @Autowired RegistryMatchService service;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private List<Case> load() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("registry/golden-lots.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                cases.add(new Case(p[0], p[1].isBlank() ? null : p[1], p[2].trim()));
            }
        }
        return cases;
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
        Map<MatchConfidence, int[]> byExpectation = new EnumMap<>(MatchConfidence.class);
        for (MatchConfidence z : MatchConfidence.values()) byExpectation.put(z, new int[3]); // NONE|GENERIC|РУ
        List<String> failures = new ArrayList<>();

        for (Case c : cases) {
            LotRegistryMatchResponse r = match(c);
            List<RegistryCandidateResponse> cands = r.getCandidates();
            String top = cands.isEmpty() ? "—" : cands.get(0).getRegNumber();

            switch (c.expectation()) {
                case "NONE" -> {
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
                case "GENERIC" -> {
                    byExpectation.get(r.getConfidence())[1]++;
                    if (r.getConfidence() != MatchConfidence.CONFIDENT) {
                        zoneHits++;
                        if (r.getConfidence() == MatchConfidence.CANNOT && !cands.isEmpty()) silentPass++;
                    } else {
                        failures.add("уверен там, где генерик: " + c.name() + " (топ " + top + ")");
                    }
                }
                default -> {
                    byExpectation.get(r.getConfidence())[2]++;
                    recallTotal++;
                    precisionTotal++;
                    boolean inTop5 = cands.stream()
                            .anyMatch(x -> c.expectation().equalsIgnoreCase(x.getRegNumber()));
                    if (inTop5) recallHits++;
                    else failures.add("нет в топ-5: " + c.name() + " (ждали " + c.expectation()
                            + ", топ " + top + ")");
                    if (!cands.isEmpty() && c.expectation().equalsIgnoreCase(cands.get(0).getRegNumber())) {
                        precisionHits++;
                    }
                    if (r.getConfidence() == MatchConfidence.CONFIDENT && inTop5) zoneHits++;
                }
            }
        }

        System.out.printf("%n=== КАЧЕСТВО РЕЕСТР-ПОДБОРА (%d кейсов) ===%n", cases.size());
        System.out.printf("recall@5:      %d/%d%n", recallHits, recallTotal);
        System.out.printf("precision@1:   %d/%d%n", precisionHits, precisionTotal);
        System.out.printf("корректность зоны: %d/%d%n", zoneHits, cases.size());
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
        failures.forEach(f -> System.out.println("  ✗ " + f));

        // Гейт мягкий намеренно: Task 5 строит инструмент и снимает baseline, а не защищает цифру.
        // Жёсткую отсечку по корректности зоны ставит Task 6 — от baseline, записанного
        // в шапке golden-lots.tsv.
    }
}
