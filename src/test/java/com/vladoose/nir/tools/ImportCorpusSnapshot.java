package com.vladoose.nir.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.config.TlsDefaults;
import com.vladoose.nir.integration.skpharmacy.SkAnnounce;
import com.vladoose.nir.integration.skpharmacy.SkLot;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyHtmlParser;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Снятие корпуса для {@code ImportCorpusReportTest}. Не тест: запускается
 * {@code ./gradlew importCorpus --args="<out-dir> <bins-file>"} с env {@code GOSZAKUP_TOKEN}.
 *
 * <p>Пишет только тексты закупок (номер, имя объявления, лоты) — без контактов и БИН.
 * СК-Фармация разбирается ТЕМ ЖЕ парсером, что в проде ({@link SkPharmacyHtmlParser}).
 */
public final class ImportCorpusSnapshot {

    private static final String GZ_ORIGIN = "https://ows.goszakup.gov.kz";
    private static final String SK_ORIGIN = "https://fms.ecc.kz";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String TRDBUY_QUERY =
            "query($o:String,$l:Int,$a:Int){ TrdBuy(filter:{orgBin:$o}, limit:$l, after:$a){ id numberAnno nameRu publishDate systemId } }";
    private static final int DESC_MAX = 600;
    private static final int SINCE_DAYS = 30;
    private static final int SK_SEARCH_PAGES = 5;
    private static final int SK_MAX_LOT_PAGES = 200;

    private final String token;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    private ImportCorpusSnapshot(String token) { this.token = token; }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: <out-dir> <bins-file>");
        String token = System.getenv("GOSZAKUP_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalStateException("нет GOSZAKUP_TOKEN");
        TlsDefaults.enableAiaFetching();   // fms.ecc.kz отдаёт цепочку без промежуточного (CLAUDE.md §14)
        Path out = Path.of(args[0]);
        List<String> bins = Files.readAllLines(Path.of(args[1])).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        new ImportCorpusSnapshot(token).run(out, bins);
    }

    private void run(Path out, List<String> bins) throws Exception {
        Files.createDirectories(out);
        List<ObjectNode> gz = snapshotGoszakup(bins);
        write(out.resolve("goszakup-zko.jsonl.gz"), gz);
        System.err.println("goszakup: объявлений " + gz.size());
        List<ObjectNode> sk = snapshotSk();
        write(out.resolve("sk-first5.jsonl.gz"), sk);
        System.err.println("СК-Фармация: объявлений " + sk.size());
    }

    // ---------------------------------------------------------------- goszakup

    private List<ObjectNode> snapshotGoszakup(List<String> bins) {
        LocalDate cutoff = LocalDate.now().minusDays(SINCE_DAYS);
        List<ObjectNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int binNo = 0;
        for (String bin : bins) {
            binNo++;
            Long after = null;
            int pages = 0;
            try {
                do {
                    JsonNode root = trdBuyPage(bin, after);
                    JsonNode items = root.path("data").path("TrdBuy");
                    boolean allOld = items.size() > 0;
                    for (JsonNode d : items) {
                        LocalDate pub = date(d.path("publishDate").asText(null));
                        if (pub == null || !pub.isBefore(cutoff)) allOld = false;
                        if (pub != null && pub.isBefore(cutoff)) continue;
                        JsonNode sys = d.path("systemId");
                        if (!(sys.isMissingNode() || sys.isNull() || sys.asInt() == 3)) continue;
                        String anno = d.path("numberAnno").asText(null);
                        if (anno == null || !seen.add(anno)) continue;
                        try {
                            result.add(goszakupTender(anno, d.path("nameRu").asText(null)));
                        } catch (Exception e) {
                            System.err.println("goszakup " + anno + ": " + e);
                        }
                    }
                    pages++;
                    if (allOld) break;
                    JsonNode pi = root.path("extensions").path("pageInfo");
                    after = (items.size() > 0 && pi.path("hasNextPage").asBoolean(false)) ? pi.path("lastId").asLong() : null;
                } while (after != null && pages < 50);
            } catch (Exception e) {
                System.err.println("goszakup БИН #" + binNo + ": " + e);
            }
            System.err.println("goszakup: больница " + binNo + "/" + bins.size() + ", объявлений всего " + result.size());
        }
        return result;
    }

    private JsonNode trdBuyPage(String bin, Long after) throws Exception {
        ObjectNode vars = om.createObjectNode();
        vars.put("o", bin);
        vars.put("l", 50);
        if (after != null) vars.put("a", after);
        ObjectNode body = om.createObjectNode();
        body.put("query", TRDBUY_QUERY);
        body.set("variables", vars);
        HttpRequest req = HttpRequest.newBuilder(URI.create(GZ_ORIGIN + "/v3/graphql"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(om.writeValueAsBytes(body))).build();
        return om.readTree(send(req, "goszakup v3"));
    }

    private ObjectNode goszakupTender(String anno, String name) throws Exception {
        ArrayNode lots = om.createArrayNode();
        String url = GZ_ORIGIN + "/v2/lots/number-anno/" + URLEncoder.encode(anno, StandardCharsets.UTF_8) + "?limit=500";
        int guard = 0;
        while (url != null && guard++ < 50) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            JsonNode page = om.readTree(send(req, "goszakup lots"));
            for (JsonNode l : page.path("items")) {
                lots.add(lot(text(l, "lot_number"), text(l, "name_ru"), text(l, "description_ru")));
            }
            String next = text(page, "next_page");
            url = (next == null || next.isBlank()) ? null : (next.startsWith("http") ? next : GZ_ORIGIN + next);
        }
        return tender("GOSZAKUP", anno, name, null, lots);
    }

    // ---------------------------------------------------------------- СК-Фармация

    private List<ObjectNode> snapshotSk() {
        List<ObjectNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int p = 1; p <= SK_SEARCH_PAGES; p++) {
            List<SkAnnounce> anns;
            try {
                anns = SkPharmacyHtmlParser.parseSearch(skGet(SK_ORIGIN + "/ru/searchanno" + (p > 1 ? "?page=" + p : "")));
            } catch (Exception e) {
                System.err.println("СК стр. " + p + ": " + e);
                continue;
            }
            for (SkAnnounce a : anns) {
                if (a.announceId() == null || !seen.add(a.announceId())) continue;
                try {
                    ObjectNode t = skTender(a);
                    if (t != null) result.add(t);
                } catch (Exception e) {
                    System.err.println("СК " + a.numberAnno() + ": " + e);
                }
            }
            System.err.println("СК: стр. ленты " + p + "/" + SK_SEARCH_PAGES + ", объявлений всего " + result.size());
        }
        return result;
    }

    private ObjectNode skTender(SkAnnounce a) throws Exception {
        ArrayNode lots = om.createArrayNode();
        Set<String> codes = new HashSet<>();
        for (int page = 1; page <= SK_MAX_LOT_PAGES; page++) {
            String html = skGet(SK_ORIGIN + "/ru/announce/index/" + a.announceId() + "?tab=lots" + (page > 1 ? "&page=" + page : ""));
            if (page == 1 && SkPharmacyHtmlParser.isServicesLotsPage(html)) return null;   // как в проде: услуги пропускаем
            int added = 0;
            for (SkLot l : SkPharmacyHtmlParser.parseLots(html)) {
                if (codes.add(String.valueOf(l.code()))) { lots.add(lot(l.code(), l.name(), l.description())); added++; }
            }
            if (added == 0 || !SkPharmacyHtmlParser.hasNextLotsPage(html, page)) break;
        }
        return tender("SK_PHARMACY", a.numberAnno(), a.nameRu(), a.lotsCount(), lots);
    }

    private String skGet(String url) throws Exception {
        Thread.sleep(300);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ru,en")
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException("fms.ecc.kz: HTTP " + resp.statusCode());
        return resp.body();
    }

    // ---------------------------------------------------------------- общее

    /** Запрос goszakup: пауза 100 мс; в тексте ошибки — только код, без адреса и токена. */
    private String send(HttpRequest req, String where) throws Exception {
        Thread.sleep(100);
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException(where + ": HTTP " + resp.statusCode());
        return resp.body();
    }

    private ObjectNode tender(String platform, String anno, String name, Integer lotsCount, ArrayNode lots) {
        ObjectNode t = om.createObjectNode();
        t.put("platform", platform);
        t.put("anno", anno);
        t.put("name", name);
        if (lotsCount == null) t.putNull("lotsCount"); else t.put("lotsCount", lotsCount);
        t.set("lots", lots);
        return t;
    }

    private ObjectNode lot(String code, String name, String description) {
        ObjectNode l = om.createObjectNode();
        l.put("code", code);
        l.put("name", name);
        l.put("description", description == null ? null
                : description.length() > DESC_MAX ? description.substring(0, DESC_MAX) : description);
        return l;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    private void write(Path file, List<ObjectNode> rows) throws Exception {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
            for (ObjectNode r : rows) {
                w.write(om.writeValueAsString(r));
                w.newLine();
            }
        }
    }
}
