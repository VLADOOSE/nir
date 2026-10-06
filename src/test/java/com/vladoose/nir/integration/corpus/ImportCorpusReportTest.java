package com.vladoose.nir.integration.corpus;

import com.vladoose.nir.integration.LotText;
import com.vladoose.nir.integration.corpus.ImportCorpus.CorpusLot;
import com.vladoose.nir.integration.corpus.ImportCorpus.CorpusTender;
import com.vladoose.nir.integration.goszakup.MedicalRelevanceFilter;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyRelevanceFilter;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Замер фильтров релевантности импорта на замороженном корпусе (спека «не терять тендеры» §6).
 * {@code report()} печатает «было/стало», {@code golden()} закрепляет ручную разметку лотов.
 */
class ImportCorpusReportTest {

    static final String GOSZAKUP = "/import-corpus/goszakup-zko.jsonl.gz";
    static final String SK = "/import-corpus/sk-first5.jsonl.gz";
    static final String GOLDEN = "/import-corpus/golden-lots.tsv";

    // ---- новые предикаты (goszakup — новый фильтр с Task 2, СК — пока старый) ----

    static boolean newGoszakup(CorpusTender t) {
        return MedicalRelevanceFilter.isRelevant(t.name(),
                t.lots().stream().map(l -> new LotText(l.name(), l.description())).toList());
    }

    static boolean newGoszakupLot(String name, String description) {
        return MedicalRelevanceFilter.isMedicalLot(new LotText(name, description));
    }

    static boolean newSk(CorpusTender t) {
        if (!SkPharmacyRelevanceFilter.nameCandidate(t.name())) return false;
        return SkPharmacyRelevanceFilter.isRelevant(t.name(), t.lots().stream().map(CorpusLot::name).toList());
    }

    static boolean newSkLot(String name, String description) {
        return SkPharmacyRelevanceFilter.isDeviceLot(name);
    }

    @Test
    void report() {
        List<CorpusTender> gz = ImportCorpus.load(GOSZAKUP);
        List<CorpusTender> sk = ImportCorpus.load(SK);
        assertThat(gz).isNotEmpty();
        assertThat(sk).isNotEmpty();
        print("GOSZAKUP", gz, LegacyRelevanceFilters::goszakup, ImportCorpusReportTest::newGoszakup,
                LegacyRelevanceFilters::goszakupLot, l -> newGoszakupLot(l.name(), l.description()));
        print("SK_PHARMACY", sk, LegacyRelevanceFilters::sk, ImportCorpusReportTest::newSk,
                LegacyRelevanceFilters::skLot, l -> newSkLot(l.name(), l.description()));
    }

    private static void print(String platform, List<CorpusTender> corpus,
                              Predicate<CorpusTender> oldT, Predicate<CorpusTender> newT,
                              Predicate<CorpusLot> oldL, Predicate<CorpusLot> newL) {
        long tOld = corpus.stream().filter(oldT).count();
        long tNew = corpus.stream().filter(newT).count();
        long lOld = corpus.stream().flatMap(t -> t.lots().stream()).filter(oldL).count();
        long lNew = corpus.stream().flatMap(t -> t.lots().stream()).filter(newL).count();
        System.out.println("REPORT " + platform + " tenders old=" + tOld + " new=" + tNew
                + " lots old=" + lOld + " new=" + lNew);
    }

    @Test
    @Disabled("включается в Task 3 — фильтры ещё старые")
    void golden() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        try (InputStream in = ImportCorpusReportTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as(GOLDEN).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                if (c.length < 3) fail("golden-lots.tsv:" + no + ": нужно platform<TAB>expected<TAB>name[<TAB>description]");
                String platform = c[0].trim();
                boolean expected = "1".equals(c[1].trim());
                String name = c[2];
                String description = c.length > 3 ? c[3] : "";
                boolean actual = switch (platform) {
                    case "GOSZAKUP" -> newGoszakupLot(name, description);
                    case "SK_PHARMACY" -> newSkLot(name, description);
                    default -> throw new IllegalArgumentException("golden-lots.tsv:" + no + ": платформа " + platform);
                };
                rows++;
                if (actual != expected) {
                    mismatches.add(no + ": " + platform + " ждали " + (expected ? 1 : 0) + " — «" + name + "»"
                            + (description.isBlank() ? "" : " / «" + description + "»"));
                }
            }
        }
        assertThat(rows).isPositive();
        if (!mismatches.isEmpty()) {
            fail("golden-lots.tsv: расхождений " + mismatches.size() + " из " + rows + ":\n" + String.join("\n", mismatches));
        }
    }
}
