package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.integration.goszakup.dto.KatoRefPageDto;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyV3PageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class GoszakupHttpClient implements GoszakupClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
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

    @Override
    public TrdBuyV3PageDto fetchTrdBuyPageByKato(List<String> katoCodes, Long after) {
        // v3 GraphQL: единственный способ серверно сузить ленту до региона (фильтр kato —
        // массив точных 9-значных кодов). Алиасы приводят поля ответа к snake_case v2,
        // чтобы парсить тем же TrdBuyDto. Пагинация: after=lastId, сортировка id DESC.
        String query = "query($k:[String],$l:Int,$a:Int){ TrdBuy(filter:{kato:$k}, limit:$l, after:$a){ "
                + "id number_anno:numberAnno name_ru:nameRu total_sum:totalSum "
                + "ref_buy_status_id:refBuyStatusId customer_bin:customerBin org_bin:orgBin "
                + "publish_date:publishDate end_date:endDate system_id:systemId } }";
        try {
            ObjectNode vars = objectMapper.createObjectNode();
            vars.set("k", objectMapper.valueToTree(katoCodes));
            vars.put("l", pageSize);
            if (after != null) vars.put("a", after);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);

            JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (root.path("errors").size() > 0) {
                throw new IllegalStateException("goszakup v3 GraphQL: " + root.get("errors"));
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
            throw new IllegalStateException("goszakup v3: разбор JSON: " + e.getMessage(), e);
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
        // лоты приходят в обёртке-странице {items:[...]}
        TypeRefPage<LotDto> page = get(baseUrl + "/lots/number-anno/" + enc(numberAnno),
                new TypeReference<TypeRefPage<LotDto>>() {});
        return page != null && page.items != null ? page.items : List.of();
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
            throw new IllegalStateException("goszakup: разбор JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu) {
        // живой формат подтверждён 2026-07-04: Files лежат на уровне лота, техспека — nameRu="Техническая спецификация"
        String query = "query($anno:String,$l:Int){ Lots(filter:{trdBuyNumberAnno:$anno}, limit:$l){ "
                + "lotNumber nameRu Files{ nameRu originalName filePath } } }";
        // площадка периодически моргает timeout — ретраим транзиентные сбои (ручная кнопка «ТЗ», оператор ждёт)
        return GoszakupRetry.withRetries(3, 400, () -> {
            try {
                ObjectNode vars = objectMapper.createObjectNode();
                vars.put("anno", numberAnno);
                vars.put("l", 100); // многолотовые тендеры — до ~50 лотов
                ObjectNode body = objectMapper.createObjectNode();
                body.put("query", query);
                body.set("variables", vars);
                JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
                if (root.path("errors").size() > 0) {
                    throw new IllegalStateException("goszakup v3 GraphQL: " + root.get("errors"));
                }
                return parseLotTechSpec(root, lotNameRu);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("goszakup v3: разбор JSON: " + e.getMessage(), e);
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
        return GoszakupRetry.withRetries(3, 400, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofSeconds(60)).build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 404) return null; // файл удалили/протух hash — вызывающий решает (404 к лоту)
                if (resp.statusCode() / 100 != 2) {
                    throw new IllegalStateException("goszakup download " + resp.statusCode() + " на " + url);
                }
                return resp.body();
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("goszakup download недоступен: " + e.getMessage(), e);
            }
        });
    }

    // --- helpers ---
    /** Тонкая обёртка-страница для эндпоинтов, отдающих {items:[...]}. */
    static class TypeRefPage<T> { public List<T> items; }

    private String origin() {
        // baseUrl="https://ows.goszakup.gov.kz/v2" → origin="https://ows.goszakup.gov.kz"
        int i = baseUrl.indexOf("/", baseUrl.indexOf("://") + 3);
        return i > 0 ? baseUrl.substring(0, i) : baseUrl;
    }
    private String graphqlUrl() { return origin() + "/v3/graphql"; }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    private <T> T get(String url, Class<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private <T> T get(String url, TypeReference<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private byte[] rawGet(String url) {
        return raw(HttpRequest.newBuilder(URI.create(url)).GET(), url);
    }
    private byte[] rawPost(String url, byte[] body) {
        return raw(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)), url);
    }
    private byte[] raw(HttpRequest.Builder builder, String url) {
        try {
            HttpRequest req = builder
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30)).build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                throw new GoszakupNotFoundException("goszakup 404 на " + url);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("goszakup API " + resp.statusCode() + " на " + url);
            }
            return resp.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("goszakup API недоступно: " + e.getMessage(), e);
        }
    }
    private interface Parser<T> { T apply(byte[] b) throws java.io.IOException; }
    private <T> T parse(byte[] body, Parser<T> p) {
        try { return p.apply(body); }
        catch (java.io.IOException e) { throw new IllegalStateException("goszakup: разбор JSON: " + e.getMessage(), e); }
    }
}
