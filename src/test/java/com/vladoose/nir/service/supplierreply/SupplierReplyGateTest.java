package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сопоставления на настоящих КП поставщиков (спека 2026-10-15 §7): «перепутано» = 0 — уверенная пара не с той
 * строкой или там, где строки нет; «подставлено верно» — не меньше замера. Храповик: упало — разбираться, а не
 * понижать APPLIED_MIN и не трогать пороги ради зелёного (как у реестра, CLAUDE.md §8 «Гейт — RegistryMatchQualityTest»).
 */
class SupplierReplyGateTest {

    /**
     * Замер на golden.txt при реализации (задача 3, 2026-10-15): подставлено верно 21 из 22 строк с ценой, подсказок 1,
     * перепутано 0. Подсказка — «Пинцет биполярный изогнутый 195 мм»: отрыв от соседнего «прямого» 0,145 < AUTO_MARGIN
     * (тесная пара, план это предсказывал — не дефект).
     */
    static final int APPLIED_MIN = 21;

    record Golden(String file, String position, int quantity, Integer row) {}

    @Test
    void nothingMixedUp_appliedNotBelowMeasure() throws Exception {
        Map<String, List<Golden>> byFile = new LinkedHashMap<>();
        for (Golden g : golden()) byFile.computeIfAbsent(g.file(), k -> new ArrayList<>()).add(g);
        int applied = 0, suggested = 0, priced = 0;
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, List<Golden>> e : byFile.entrySet()) {
            ReplyTable t = ReplyTableReader.read(ReplyTableReaderTest.res(e.getKey()), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
            Map<Integer, ReplyRowValues> byRow = new HashMap<>();
            List<ReplyMatcher.Row> rows = new ArrayList<>();
            int order = 0;
            for (ReplyRowValues v : ReplyRowValues.of(t)) {
                byRow.put(v.fileRow(), v);
                if (v.participates()) {
                    rows.add(new ReplyMatcher.Row(v.fileRow(), order++, v.matchText(), v.quantity() == null ? null : v.quantity().value()));
                }
            }
            List<Golden> gs = e.getValue();
            List<ReplyMatcher.Position> positions = new ArrayList<>();
            for (int i = 0; i < gs.size(); i++) {
                Golden g = gs.get(i);
                positions.add(new ReplyMatcher.Position(i, i + 1, g.position(), g.quantity()));
                if (g.row() != null && byRow.containsKey(g.row()) && byRow.get(g.row()).price() != null) priced++;
            }
            for (ReplyMatcher.Match m : ReplyMatcher.match(positions, rows)) {
                Golden g = gs.get((int) m.itemId());
                if (m.kind() == ReplyMatcher.Kind.SUGGEST) {
                    suggested++;
                } else if (g.row() == null || g.row() != m.fileRow()) {
                    wrong.add(e.getKey() + ": «" + g.position() + "» → строка " + m.fileRow()
                            + " (ждали " + (g.row() == null ? "«нет в файле»" : "строку " + g.row()) + ")");
                } else if (byRow.get(m.fileRow()).price() != null) {
                    applied++;
                }
            }
        }
        System.out.printf("REPORT supplier-reply gate: applied=%d of priced=%d, suggested=%d, wrong=%d%n",
                applied, priced, suggested, wrong.size());
        assertThat(wrong).as("перепутано").isEmpty();
        assertThat(applied).as("подставлено верно").isGreaterThanOrEqualTo(APPLIED_MIN);
    }

    static List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                SupplierReplyGateTest.class.getResourceAsStream("/supplier-reply/golden.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                String row = f[3].trim();
                out.add(new Golden(f[0].trim(), f[1].trim(), Integer.parseInt(f[2].trim()), row.equals("-") ? null : Integer.valueOf(row)));
            }
        }
        return out;
    }
}
