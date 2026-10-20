package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.integration.goszakup.dto.ContractInfo;
import com.vladoose.nir.integration.goszakup.dto.FoundAnnouncement;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.integration.goszakup.dto.KatoRefPageDto;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyV3PageDto;
import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.http.UpstreamIoException;
import com.vladoose.nir.integration.http.UpstreamRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class GoszakupHttpClient implements GoszakupClient {

    /** Предел JSON-ответа API (страница ленты/лотов — сотни КБ). */
    static final long MAX_JSON_BYTES = 10L * 1024 * 1024;
    /** Предел файла техспеки (PDF). */
    static final long MAX_FILE_BYTES = 30L * 1024 * 1024;
    /** Предохранитель обхода страниц лотов: 40 × 50 = 2000 лотов — с запасом над живыми объявлениями. */
    static final int MAX_LOT_PAGES = 40;
    private static final Logger log = LoggerFactory.getLogger(GoszakupHttpClient.class);
    private static final Duration API_DEADLINE = Duration.ofSeconds(60);
    private static final Duration FILE_DEADLINE = Duration.ofSeconds(120);

    /** Без редиректов: токен уходит в заголовке, и переход на чужой хост унёс бы его туда. */
    private final HttpClient http = UpstreamHttp.newClient(HttpClient.Redirect.NEVER);
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;
    private final int pageSize;

    public GoszakupHttpClient(ObjectMapper objectMapper,
                              @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                              @Value("${goszakup.api.token:}") String token,
                              @Value("${goszakup.api.page-size:50}") int pageSize) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.pageSize = pageSize;
    }

    @Override public boolean isConfigured() { return token != null && !token.isBlank(); }

    @Override
    public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        // cursor — это путь next_page ("/v2/trd-buy?page=next&search_after=...") либо null
        String url = (cursor != null && !cursor.isBlank())
                ? origin() + cursor
                : baseUrl + "/trd-buy?limit=" + pageSize;
        return get(url, TrdBuyPageDto.class);
    }

    private static final String V3_FIELDS =
            "id number_anno:numberAnno name_ru:nameRu total_sum:totalSum "
          + "ref_buy_status_id:refBuyStatusId customer_bin:customerBin org_bin:orgBin "
          + "publish_date:publishDate end_date:endDate system_id:systemId";

    @Override
    public TrdBuyV3PageDto fetchTrdBuyPageByKato(List<String> katoCodes, Long after) {
        // v3 GraphQL: серверно сузить ленту до региона (фильтр kato — массив точных 9-значных кодов).
        String query = "query($k:[String],$l:Int,$a:Int){ TrdBuy(filter:{kato:$k}, limit:$l, after:$a){ "
                + V3_FIELDS + " } }";
        ObjectNode vars = objectMapper.createObjectNode();
        vars.set("k", objectMapper.valueToTree(katoCodes));
        vars.put("l", pageSize);
        if (after != null) vars.put("a", after);
        return postTrdBuyV3(query, vars);
    }

    @Override
    public TrdBuyV3PageDto fetchTrdBuyPageByOrgBin(String orgBin, Long after) {
        // v3 GraphQL: лента одной организации-заказчика по её БИН (orgBin — валидный фильтр TrdBuy).
        String query = "query($o:String,$l:Int,$a:Int){ TrdBuy(filter:{orgBin:$o}, limit:$l, after:$a){ "
                + V3_FIELDS + " } }";
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("o", orgBin);
        vars.put("l", pageSize);
        if (after != null) vars.put("a", after);
        return postTrdBuyV3(query, vars);
    }

    /** Общий POST v3-запроса TrdBuy: тело, разбор items, вычисление nextAfter из pageInfo. */
    private TrdBuyV3PageDto postTrdBuyV3(String query, ObjectNode vars) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);
            JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (root.path("errors").size() > 0) {
                throw new GoszakupCallException("goszakup v3 GraphQL: " + root.get("errors"), false, null);
            }
            List<TrdBuyDto> items = new java.util.ArrayList<>();
            for (JsonNode n : root.path("data").path("TrdBuy")) {
                items.add(objectMapper.treeToValue(n, TrdBuyDto.class));
            }
            JsonNode pageInfo = root.path("extensions").path("pageInfo");
            Long nextAfter = (!items.isEmpty() && pageInfo.path("hasNextPage").asBoolean(false))
                    ? pageInfo.path("lastId").asLong() : null;
            TrdBuyV3PageDto page = new TrdBuyV3PageDto();
            page.setItems(items);
            page.setNextAfter(nextAfter);
            return page;
        } catch (java.io.IOException e) {
            throw new GoszakupCallException("goszakup v3: разбор JSON: " + e.getMessage(), false, e);
        }
    }

    @Override
    public KatoRefPageDto fetchKatoPage(String cursor) {
        String url = (cursor != null && !cursor.isBlank())
                ? origin() + cursor
                : baseUrl + "/refs/ref_kato?limit=500"; // 500 — потолок страницы справочника
        return get(url, KatoRefPageDto.class);
    }

    @Override
    public List<LotDto> fetchLots(String numberAnno) {
        // живой API (2026-10-07): страница {total, limit, next_page, items}; limit=500 принимается, next_page
        // ведёт без limit (страницы по 50) — идём по нему до конца и сверяем с total: неполный ответ = сбой
        List<LotDto> all = new ArrayList<>();
        String url = baseUrl + "/lots/number-anno/" + enc(numberAnno) + "?limit=500";
        Integer total = null;
        for (int page = 0; page < MAX_LOT_PAGES && url != null; page++) {
            LotsPage p = get(url, LotsPage.class);
            if (p == null) break;
            if (total == null) total = p.total;
            if (p.items != null) all.addAll(p.items);
            url = nextPageUrl(p.nextPage);
        }
        if (total != null && all.size() < total) {
            throw new GoszakupCallException("goszakup: лоты объявления " + numberAnno + " — получено " + all.size()
                    + " из " + total, false, null);
        }
        return all;
    }

    /** Страница лотов v2. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LotsPage {
        public Integer total;
        @JsonProperty("next_page") public String nextPage;
        public List<LotDto> items;
    }

    @Override
    public SubjectDto fetchSubject(String bin) {
        if (bin == null || bin.isBlank()) return null;
        try {
            // поиск по БИН — /subject/biin/{биин} (/subject/{id} — по внутреннему id);
            // на неизвестный БИН живой API отвечает 200 и "[]", не 404
            byte[] body = rawGet(baseUrl + "/subject/biin/" + enc(bin));
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) return null;
            return objectMapper.treeToValue(node, SubjectDto.class);
        } catch (GoszakupNotFoundException notFound) {
            return null; // организации нет в реестре — регион просто не определится
        } catch (java.io.IOException e) {
            throw new GoszakupCallException("goszakup: разбор JSON: " + e.getMessage(), false, e);
        }
    }

    @Override
    public LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu) {
        // живой формат подтверждён 2026-07-04: Files лежат на уровне лота, техспека — nameRu="Техническая спецификация"
        String query = "query($anno:String,$l:Int){ Lots(filter:{trdBuyNumberAnno:$anno}, limit:$l){ "
                + "lotNumber nameRu Files{ nameRu originalName filePath } } }";
        // площадка периодически моргает timeout — ретраим транзиентные сбои (ручная кнопка «ТЗ», оператор ждёт)
        return UpstreamRetry.call(UpstreamRetry.REAL, () -> {
            try {
                ObjectNode vars = objectMapper.createObjectNode();
                vars.put("anno", numberAnno);
                vars.put("l", 100); // многолотовые тендеры — до ~50 лотов
                ObjectNode body = objectMapper.createObjectNode();
                body.put("query", query);
                body.set("variables", vars);
                JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
                if (root.path("errors").size() > 0) {
                    throw new GoszakupCallException("goszakup v3 GraphQL: " + root.get("errors"), false, null);
                }
                return parseLotTechSpec(root, lotNameRu);
            } catch (java.io.IOException e) {
                throw new GoszakupCallException("goszakup v3: разбор JSON: " + e.getMessage(), false, e);
            }
        });
    }

    /** Чистая функция: из ответа v3 Lots достать файл техспеки лота по name_ru (trim, case-insensitive). */
    static LotTechSpecRef parseLotTechSpec(JsonNode root, String lotNameRu) {
        String wanted = lotNameRu == null ? "" : lotNameRu.trim();
        LotTechSpecRef first = null;
        int matched = 0;
        for (JsonNode lot : root.path("data").path("Lots")) {
            if (!wanted.equalsIgnoreCase(lot.path("nameRu").asText("").trim())) continue;
            for (JsonNode f : lot.path("Files")) {
                // имя файла варьируется: «Техническая спецификация» или «Приложение 13 (Техническая
                // спецификация закупаемых товаров)» → матч по вхождению, не точному равенству
                if (!f.path("nameRu").asText("").toLowerCase().contains("техническая спецификация")) continue;
                matched++;
                if (first == null) {
                    first = new LotTechSpecRef(f.path("filePath").asText(null),
                            f.path("originalName").asText(null), false);
                }
                break; // один файл техспеки на лот
            }
        }
        if (first == null) return null;
        return matched > 1 ? new LotTechSpecRef(first.filePath(), first.originalName(), true) : first;
    }

    @Override
    public byte[] downloadFile(String url) {
        // без Accept: application/json — отдаётся бинарник (octet-stream); ретрай на транзиентный timeout
        return UpstreamRetry.call(UpstreamRetry.REAL, () -> {
            HttpRequest req = requestFor(url)
                    .GET()
                    .header("Authorization", "Bearer " + token)
                    .timeout(FILE_DEADLINE).build();
            HttpResponse<byte[]> resp;
            try {
                resp = UpstreamHttp.exchange(http, req, MAX_FILE_BYTES, FILE_DEADLINE);
            } catch (UpstreamIoException e) {
                throw new GoszakupCallException("goszakup download недоступен: " + e.getMessage(), e.retryable(), e);
            }
            int code = resp.statusCode();
            if (code == 404) return null; // файл удалили/протух hash — вызывающий решает (404 к лоту)
            if (code / 100 != 2) {
                throw new GoszakupCallException("goszakup download " + code, UpstreamRetry.retryableStatus(code), null);
            }
            return resp.body();
        });
    }

    // --- поиск по стране (v3). Наружу — через бин GoszakupSearchHttpClient со СВОИМ экземпляром этого клиента:
    // тест, подменяющий GoszakupClient моком (@MockitoBean), подменил бы и поиск, будь он тем же бином. ---

    private static final String BUY_FIELDS = "id numberAnno nameRu orgBin orgNameRu totalSum publishDate endDate refBuyStatusId "
            + "refTradeMethodsId systemId RefTradeMethods{ nameRu }";
    private static final String LOT_FIELDS = "id lotNumber nameRu descriptionRu count amount refLotStatusId lastUpdateDate "
            + "customerBin customerNameRu trdBuyNumberAnno TrdBuy{ " + BUY_FIELDS + " }";
    private static final String PLAN_FIELDS = "id plnPointYear refMonthsId subjectBiin subjectNameRu nameRu descRu extraDescRu count "
            + "price amount refPlnPointStatusId RefUnits{ nameRu } RefTradeMethods{ nameRu } RefMonths{ nameRu } "
            + "RefPlnPointStatus{ nameRu } PlansKato{ fullDeliveryPlaceNameRu }";
    private static final int SEARCH_PAGE = 200;

    /** Поле фильтра v3: имя, тип GraphQL и значение (null — поле не ставится). */
    private record F(String name, String type, Object value) {}

    /** Запрос `root(filter:{…}, limit, after){ fields }` → корневой узел ответа (data.root) и курсор. */
    private JsonNode v3(String root, String fields, List<F> filters, int limit, Long after, Long[] next) {
        StringBuilder decl = new StringBuilder("$l:Int,$a:Int");
        StringBuilder flt = new StringBuilder();
        ObjectNode vars = objectMapper.createObjectNode();
        int i = 0;
        for (F f : filters) {
            if (f.value() == null) continue;
            String v = "v" + (i++);
            decl.append(",$").append(v).append(':').append(f.type());
            if (flt.length() > 0) flt.append(',');
            flt.append(f.name()).append(":$").append(v);
            vars.set(v, objectMapper.valueToTree(f.value()));
        }
        vars.put("l", limit);
        if (after != null) vars.put("a", after);
        String query = "query(" + decl + "){ " + root + "(filter:{" + flt + "}, limit:$l, after:$a){ " + fields + " } }";
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);
            JsonNode r = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (r.path("errors").size() > 0) throw new GoszakupCallException("goszakup v3 GraphQL: " + r.get("errors"), false, null);
            JsonNode items = r.path("data").path(root);
            JsonNode pi = r.path("extensions").path("pageInfo");
            if (next != null) next[0] = items.size() > 0 && pi.path("hasNextPage").asBoolean(false) ? pi.path("lastId").asLong() : null;
            return items;
        } catch (java.io.IOException e) {
            throw new GoszakupCallException("goszakup v3: разбор JSON: " + e.getMessage(), false, e);
        }
    }


    private static List<String> range(LocalDate from, LocalDate to) {
        return from == null || to == null ? null : List.of(from.toString(), to.toString());
    }

    private static String text(JsonNode n, String f) { JsonNode x = n.path(f); return x.isMissingNode() || x.isNull() ? null : x.asText(); }
    private static Integer integer(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.asInt() : null; }
    private static Long longer(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.asLong() : null; }
    private static java.math.BigDecimal dec(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.decimalValue() : null; }

    private static FoundAnnouncement announcementOf(JsonNode b) {
        if (b == null || b.isMissingNode() || b.isNull()) return null;
        return new FoundAnnouncement(longer(b, "id"), text(b, "numberAnno"), text(b, "nameRu"), text(b, "orgBin"),
                text(b, "orgNameRu"), dec(b, "totalSum"), text(b, "publishDate"), text(b, "endDate"),
                integer(b, "refBuyStatusId"), integer(b, "refTradeMethodsId"), text(b.path("RefTradeMethods"), "nameRu"),
                integer(b, "systemId"));
    }

    private static FoundLot lotOf(JsonNode l) {
        return new FoundLot(longer(l, "id"), text(l, "lotNumber"), text(l, "nameRu"), text(l, "descriptionRu"), dec(l, "count"),
                dec(l, "amount"), integer(l, "refLotStatusId"), text(l, "lastUpdateDate"), text(l, "customerBin"),
                text(l, "customerNameRu"), text(l, "trdBuyNumberAnno"), announcementOf(l.path("TrdBuy")));
    }

    private static FoundPlanPoint planOf(JsonNode p) {
        JsonNode kato = p.path("PlansKato");
        String place = kato.isArray() && kato.size() > 0 ? text(kato.get(0), "fullDeliveryPlaceNameRu") : null;
        return new FoundPlanPoint(longer(p, "id"), integer(p, "plnPointYear"), integer(p, "refMonthsId"),
                text(p.path("RefMonths"), "nameRu"), text(p, "subjectBiin"), text(p, "subjectNameRu"), text(p, "nameRu"),
                text(p, "descRu"), text(p, "extraDescRu"), dec(p, "count"), text(p.path("RefUnits"), "nameRu"), dec(p, "price"),
                dec(p, "amount"), text(p.path("RefTradeMethods"), "nameRu"), integer(p, "refPlnPointStatusId"),
                text(p.path("RefPlnPointStatus"), "nameRu"), place);
    }

    private static <T> List<T> map(JsonNode items, java.util.function.Function<JsonNode, T> f) {
        List<T> out = new ArrayList<>();
        for (JsonNode n : items) out.add(f.apply(n));
        return out;
    }

    public SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate from, LocalDate to, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("Lots", LOT_FIELDS, List.of(new F("nameDescriptionRu", "String", word),
                new F("refLotStatusId", "[Int]", lotStatuses), new F("lastUpdateDate", "[String]", range(from, to))),
                SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::lotOf), next[0]);
    }

    public SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate from, LocalDate to, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("TrdBuy", BUY_FIELDS, List.of(new F("commonName", "String", word),
                new F("refBuyStatusId", "[Int]", buyStatuses), new F("publishDate", "[String]", range(from, to))),
                SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::announcementOf), next[0]);
    }

    public FoundAnnouncement announcement(String numberAnno) {
        JsonNode items = v3("TrdBuy", BUY_FIELDS, List.of(new F("numberAnno", "String", numberAnno)), 1, null, null);
        return items.size() == 0 ? null : announcementOf(items.get(0));
    }

    public List<FoundLot> lotsOfAnnouncement(String numberAnno) {
        return map(v3("Lots", LOT_FIELDS, List.of(new F("trdBuyNumberAnno", "String", numberAnno)), 100, null, null),
                GoszakupHttpClient::lotOf);
    }

    public SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("Plans", PLAN_FIELDS, List.of(new F("nameDescriptionRu", "String", word),
                new F("plnPointYear", "[Int]", years), new F("refPlnPointStatusId", "[Int]", statuses)), SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::planOf), next[0]);
    }

    public List<FoundPlanPoint> plansById(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Integer> asInt = ids.stream().map(Long::intValue).toList();   // id в фильтре Plans — [Int]
        return map(v3("Plans", PLAN_FIELDS, List.of(new F("id", "[Int]", asInt)), Math.min(200, ids.size()), null, null),
                GoszakupHttpClient::planOf);
    }

    public List<ContractInfo> contracts(String numberAnno) {
        return map(v3("Contract", "supplierBiin contractSum contractSumWnds signDate refContractStatusId",
                List.of(new F("trdBuyNumberAnno", "String", numberAnno)), 20, null, null),
                c -> new ContractInfo(text(c, "supplierBiin"), dec(c, "contractSum"), dec(c, "contractSumWnds"),
                        text(c, "signDate"), integer(c, "refContractStatusId")));
    }

    // --- helpers ---

    private String origin() {
        // baseUrl="https://ows.goszakup.gov.kz/v2" → origin="https://ows.goszakup.gov.kz"
        int i = baseUrl.indexOf("/", baseUrl.indexOf("://") + 3);
        return i > 0 ? baseUrl.substring(0, i) : baseUrl;
    }
    private String graphqlUrl() { return origin() + "/v3/graphql"; }

    /**
     * Адрес следующей страницы лотов. Площадка присылает путь «/v2/…» — он склеивается с нашим origin. Абсолютный
     * адрес принимается, только если он на нашем же хосте: по чужому ушёл бы токен из заголовка. Остальное
     * (чужой хост, путь без «/» — склейка поменяла бы хост) не идём — список останется неполным и это
     * честно станет ошибкой «получено M из K».
     */
    private String nextPageUrl(String next) {
        if (next == null || next.isBlank()) return null;
        if (next.startsWith("/") && !next.startsWith("//")) return origin() + next;
        if (next.startsWith(origin() + "/")) return next;
        log.warn("goszakup: next_page лотов не на хосте API — не идём");
        return null;
    }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    private <T> T get(String url, Class<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private byte[] rawGet(String url) {
        return raw(requestFor(url).GET(), url);
    }
    private byte[] rawPost(String url, byte[] body) {
        return raw(requestFor(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)), url);
    }

    /**
     * Сборка запроса: битый адрес (пробел в next_page от площадки, опечатка в настройке) — ошибка goszakup без
     * самого адреса в тексте. Голый {@code IllegalArgumentException} JDK несёт адрес целиком и улетел бы в
     * итог прогона, а повтор такой ошибки не лечит.
     */
    private static HttpRequest.Builder requestFor(String url) {
        try {
            return HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new GoszakupCallException("goszakup: неверный адрес запроса", false, null);
        }
    }
    private byte[] raw(HttpRequest.Builder builder, String url) {
        HttpRequest req = builder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(API_DEADLINE).build();
        HttpResponse<byte[]> resp;
        try {
            resp = UpstreamHttp.exchange(http, req, MAX_JSON_BYTES, API_DEADLINE);
        } catch (UpstreamIoException e) {
            throw new GoszakupCallException("goszakup API недоступно: " + e.getMessage(), e.retryable(), e);
        }
        int code = resp.statusCode();
        if (code == 404) {
            throw new GoszakupNotFoundException("goszakup 404 на " + pathOf(url));
        }
        if (code / 100 != 2) { // 3xx сюда же: редиректы не идём, повтор не поможет
            throw new GoszakupCallException("goszakup API " + code + " на " + pathOf(url), code,
                    UpstreamRetry.retryableStatus(code), null);
        }
        return resp.body();
    }

    /** Путь без хоста и query: в тексте для оператора не нужен ни адрес площадки, ни параметры. */
    private static String pathOf(String url) {
        try {
            return URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }
    private interface Parser<T> { T apply(byte[] b) throws java.io.IOException; }
    private <T> T parse(byte[] body, Parser<T> p) {
        try { return p.apply(body); }
        catch (java.io.IOException e) { throw new GoszakupCallException("goszakup: разбор JSON: " + e.getMessage(), false, e); }
    }
}
