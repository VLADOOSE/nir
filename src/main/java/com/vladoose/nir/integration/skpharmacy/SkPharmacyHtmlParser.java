package com.vladoose.nir.integration.skpharmacy;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер HTML портала fms.ecc.kz (СК-Фармация) — Jsoup. Чистый, юнит-тестируется на фикстурах.
 * Селекторы привязаны к текущей вёрстке; регресс ловят фикстур-тесты. Ошибки строки → строка пропущена.
 */
public final class SkPharmacyHtmlParser {

    private SkPharmacyHtmlParser() {}

    private static final Pattern ANN_ID = Pattern.compile("announce/index/(\\d+)");

    /** searchanno: table.table-bordered, строки данных по 10 td. */
    public static List<SkAnnounce> parseSearch(String html) {
        List<SkAnnounce> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Document doc = Jsoup.parse(html);
        for (Element tr : doc.select("table.table-bordered tr")) {
            Elements tds = tr.select("td");
            if (tds.size() < 10) continue;                       // заголовок (th) / чужая строка
            Element link = tr.selectFirst("a[href*=announce/index/]");
            if (link == null) continue;
            Matcher m = ANN_ID.matcher(link.attr("href"));
            if (!m.find()) continue;
            out.add(new SkAnnounce(m.group(1), txt(tds, 0), txt(tds, 1), txt(tds, 2), txt(tds, 3),
                    txt(tds, 4), txt(tds, 5), txt(tds, 6), intOrNull(tds, 7), moneyOrNull(tds, 8), txt(tds, 9)));
        }
        return out;
    }

    /**
     * Метки колонок lots-таблицы по ролям, В ПОРЯДКЕ ПРИОРИТЕТА (нормализованные, матч подстрокой).
     * У портала ПЯТЬ вёрсток этой вкладки, и позиции колонок не совпадают ни в одной паре:
     * «закуп медтехники» (№ п/п · № лота · Наименование лота · Цена выделенная · Количество),
     * тот же вид с ДОПОЛНИТЕЛЬНЫМИ «Заказчик»/«Характеристика» (объявление 521024 — позиционный разбор писал
     * оттуда имя заказчика в наименование лота, а имя товара в цену),
     * «приказ ЕД» (№ лота · Код СПП · … · МНН · … · Предельная цена МЗ РК · Цена ЕД для закупа, колонки «Количество» НЕТ),
     * «долгосрочные договоры» (№ лота · … · наименование по МНН · наименование поставщика · … · Количество · Цена для закупа),
     * «предельные цены» (522744 — шапка на обычных td, тега th на странице нет ни одного).
     * Отсюда ключуемся по ЗАГОЛОВКАМ, а не по индексам.
     */
    private static final List<String> CODE_LABELS = List.of("№ лота", "номер лота");
    /** В вёрстке долгосрочных договоров «наименование» носят ТРИ колонки (по МНН, поставщика, по торговому);
     *  берём первую по порядку — это МНН, то есть то же, что «МНН» во второй вёрстке и «наименование лота» в первой. */
    private static final List<String> NAME_LABELS = List.of("наименование лота", "мнн", "наименование");
    /** «предельная цена МЗ РК» — потолок министерства, а не цена закупа: общей метки «цена» нет намеренно. */
    private static final List<String> PRICE_LABELS = List.of("цена ед для закупа", "цена для закупа", "цена выделенная");
    private static final List<String> QTY_LABELS = List.of("количество");
    /** Колонка-описание: у объявлений этих вёрсток PDF техспеки нет вовсе, и это единственные характеристики. */
    private static final List<String> DESC_LABELS = List.of("характеристика", "лекарственная форма");

    /** Индексы колонок одной lots-таблицы + строка-шапка (её нельзя принять за лот); code+name обязательны, price/qty могут отсутствовать (-1). */
    private record LotColumns(int code, int name, int price, int qty, int desc, Element headerRow) {
        boolean resolved() { return code >= 0 && name >= 0; }
        int width() { return Math.max(Math.max(Math.max(code, name), Math.max(price, qty)), desc) + 1; }
    }

    /**
     * Вкладка lots: колонки ищем по заголовкам (см. {@link #CODE_LABELS}), строку берём, если в ней есть
     * непустые код и наименование. Неизвестная вёрстка (code/name не нашлись) → пусто: разложить строки
     * по угаданным позициям хуже, чем не разобрать — пустой результат ловит writer и лоты остаются целы (§7).
     */
    public static List<SkLot> parseLots(String html) {
        List<SkLot> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Document doc = Jsoup.parse(html);
        for (Element table : doc.select("table")) {
            LotColumns cols = resolveColumns(table);
            if (cols == null) continue;
            for (Element tr : table.select("tr")) {
                if (tr == cols.headerRow()) continue;            // шапка бывает свёрстана обычными td — в лоты её не берём
                Elements tds = tr.select("td");
                if (tds.size() < cols.width()) continue;         // заголовок (th) / «итого» через colspan / чужая строка
                String code = txt(tds, cols.code());
                String name = txt(tds, cols.name());
                if (code.isBlank() || name.isBlank()) continue;
                out.add(new SkLot(code, name, moneyOrNull(tds, cols.price()), intOrNull(tds, cols.qty()), txt(tds, cols.desc())));
            }
            if (!out.isEmpty()) return out;                      // первая таблица с лотами — она и есть
        }
        return out;
    }

    /**
     * Заголовки таблицы → индексы колонок; не lots-таблица (нет колонок кода и наименования) → null.
     * Шапка бывает и на {@code th}, и на обычных {@code td} (объявление 522744 — тега {@code th} на странице нет
     * вообще): пробуем th-строку, иначе ПЕРВУЮ строку таблицы. Вариант с td принимается, только если по нему
     * сошлись код и наименование — то есть проверяется он сам собой, а не доверием к вёрстке.
     */
    private static LotColumns resolveColumns(Element table) {
        Element thRow = null, firstRow = null;
        for (Element tr : table.select("tr")) {
            if (firstRow == null) firstRow = tr;
            if (tr.select("th").size() >= 2) { thRow = tr; break; }
        }
        LotColumns cols = thRow != null ? columnsOf(thRow, "th") : null;
        if (cols == null && firstRow != null) cols = columnsOf(firstRow, "td");
        return cols;
    }

    private static LotColumns columnsOf(Element row, String cellTag) {
        List<String> labels = row.select(cellTag).stream().map(c -> norm(c.text())).toList();
        LotColumns cols = new LotColumns(indexOf(labels, CODE_LABELS), indexOf(labels, NAME_LABELS),
                indexOf(labels, PRICE_LABELS), indexOf(labels, QTY_LABELS), indexOf(labels, DESC_LABELS), row);
        return cols.resolved() ? cols : null;
    }

    /** Первая метка-кандидат (по приоритету), найденная среди заголовков; нет — -1. */
    private static int indexOf(List<String> labels, List<String> candidates) {
        for (String candidate : candidates) {
            for (int i = 0; i < labels.size(); i++) {
                if (labels.get(i).contains(candidate)) return i;
            }
        }
        return -1;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }

    private static final Pattern PAGE_PARAM = Pattern.compile("[?&]page=(\\d+)");

    /**
     * Есть ли в пейджере ссылка на СЛЕДУЮЩУЮ страницу лотов. Именно «есть ссылка вперёд», а не «номер меньше
     * последнего»: на первой странице портал показывает окно пейджера без последнего номера (у 522204 со стр. 1
     * видно только «2», хотя страниц 3), а за последней страницей отдаёт контент последней (page=4 = page=3),
     * то есть счёт по номерам либо оборвал бы импорт, либо зациклил.
     */
    public static boolean hasNextLotsPage(String html, int currentPage) {
        if (html == null || html.isBlank()) return false;
        for (Element a : Jsoup.parse(html).select(".pagination a[href]")) {
            Matcher m = PAGE_PARAM.matcher(a.attr("href"));
            if (m.find() && Integer.parseInt(m.group(1)) == currentPage + 1) return true;
        }
        return false;
    }

    private static final Pattern BIN = Pattern.compile("^(\\d{12})\\b");
    private static final Pattern KATO = Pattern.compile("\\b(\\d{9})\\b");

    /**
     * Вкладка «Общие сведения» (?tab=general): поля идут строками таблиц {@code <tr><th>метка</th><td>значение</td></tr>}.
     * Ключуемся по устойчивым якорям, НЕ по формулировке организатора (она разная: «Единый дистрибьютор» / «Лизингодатель»):
     * БИН — значение, начинающееся с 12 цифр; адрес — метка со словом «адрес»; e-mail — метка «mail»; контакт — метка «фио».
     * Пусто/битый HTML → null (fail-soft, тендер всё равно пишется без этих полей).
     */
    public static SkGeneral parseGeneral(String html) {
        if (html == null || html.isBlank()) return null;
        Document doc = Jsoup.parse(html);
        String bin = null, address = null, email = null, contact = null;
        for (Element tr : doc.select("tr")) {
            if (tr.select("th").size() != 1 || tr.select("td").size() != 1) continue;   // только «метка|значение», не мультиколоночные таблицы
            String label = tr.selectFirst("th").text().trim().toLowerCase();
            String value = tr.selectFirst("td").text().trim();
            if (value.isBlank()) continue;
            if (bin == null) {
                Matcher m = BIN.matcher(value);
                if (m.find()) bin = m.group(1);
            }
            if (address == null && label.contains("адрес")) address = value;
            else if (email == null && (label.contains("mail") || label.contains("почта")) && value.contains("@")) email = value;
            else if (contact == null && label.contains("фио")) contact = value;
        }
        String kato = null;
        if (address != null) {
            Matcher m = KATO.matcher(address);
            if (m.find()) kato = m.group(1);
        }
        return new SkGeneral(bin, address, kato, email, contact);
    }

    /** i < 0 — колонки нет в этой вёрстке (напр. «Количество» в вёрстке приказа ЕД). */
    private static String txt(Elements tds, int i) { return i >= 0 && i < tds.size() ? tds.get(i).text().trim() : ""; }

    private static Integer intOrNull(Elements tds, int i) {
        String s = txt(tds, i).replaceAll("[^0-9]", "");
        try { return s.isBlank() ? null : Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    /** fms формат: пробелы-тысячи + точка-десятичная («15 085 999 992.00»). */
    private static BigDecimal moneyOrNull(Elements tds, int i) {
        String s = txt(tds, i).replaceAll("[^0-9.]", "");
        if (s.isBlank()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
