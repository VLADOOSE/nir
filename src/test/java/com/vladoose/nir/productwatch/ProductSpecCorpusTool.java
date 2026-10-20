package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.GoszakupHttpClient;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Сбор набора настоящих ТЗ для калибровки (задача 6 плана). Не тест: запуск — PRODUCT_SPEC_CORPUS=1 GOSZAKUP_TOKEN=… */
@EnabledIfEnvironmentVariable(named = "PRODUCT_SPEC_CORPUS", matches = "1")
class ProductSpecCorpusTool {

    record Want(String group, String word, int max, boolean anyDate) {}

    static final List<Want> WANTS = List.of(
            new Want("Электрокардиографы", "электрокардиограф", 3, false), new Want("Дефибрилляторы", "дефибриллятор", 3, false),
            new Want("Облучатели и рециркуляторы", "облучатель", 3, false), new Want("Облучатели и рециркуляторы", "рециркулятор", 2, false),
            new Want("Светильники", "светильник", 3, false), new Want("Электрохирургия", "электрохирургический", 2, false),
            new Want("Электрохирургия", "электрокоагулятор", 2, false), new Want("Отсасыватели и аспирация дыма", "отсасыватель", 3, false),
            new Want("Отсасыватели и аспирация дыма", "аспиратор", 2, false), new Want("Стерилизаторы озоновые", "стерилизатор", 2, false),
            new Want("Дозаторы шприцевые", "дозатор", 2, false), new Want("Мониторы пациента", "монитор", 2, false),
            new Want("Нейтральные электроды", "электрод", 2, false), new Want("Спирографы", "спирометр", 1, false),
            new Want("Наши бренды и модели", "фотек", 3, true), new Want("Наши бренды и модели", "эхвч", 2, true),
            new Want("Наши бренды и модели", "аксион", 2, true));

    @Test
    void collect() throws Exception {
        GoszakupHttpClient gz = new GoszakupHttpClient(new ObjectMapper(), "https://ows.goszakup.gov.kz/v2", System.getenv("GOSZAKUP_TOKEN"), 50);
        Path dir = Paths.get("src/test/resources/product-watch/specs");
        Files.createDirectories(dir);
        Path tsv = dir.resolve("corpus.tsv");
        List<String> rows = new ArrayList<>(Files.exists(tsv) ? Files.readAllLines(tsv) : List.of("file\tgroup\tword\tlot\tdescription\tannouncement\tcustomer"));
        Set<String> customers = new HashSet<>();
        LocalDate today = LocalDate.now();
        for (Want w : WANTS) {
            int got = 0;
            Thread.sleep(1000);
            List<FoundLot> lots = gz.searchLots(w.word(), null, w.anyDate() ? null : today.minusDays(90), w.anyDate() ? null : today.plusDays(1), null).items();
            for (FoundLot l : lots) {
                if (got >= w.max() || l.announcement() == null || !customers.add(String.valueOf(l.customerBin()) + w.word())) continue;
                Thread.sleep(1000);
                LotTechSpecRef ref = gz.fetchLotTechSpec(l.trdBuyNumberAnno(), l.nameRu());
                if (ref == null || ref.filePath() == null) continue;
                Thread.sleep(1000);
                byte[] pdf = gz.downloadFile(ref.filePath());
                if (pdf == null) continue;
                String file = (l.trdBuyNumberAnno() + "_" + l.lotNumber()).replaceAll("[^\\p{L}\\p{N}-]", "_") + ".pdf";
                Files.write(dir.resolve(file), pdf);
                rows.add(String.join("\t", file, w.group(), w.word(), clean(l.nameRu()), clean(l.descriptionRu()),
                        clean(l.announcement().nameRu()), clean(l.customerNameRu())));
                got++;
            }
        }
        Files.write(tsv, rows);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }
}
