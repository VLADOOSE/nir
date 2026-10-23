package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.dto.response.SupplierReplyView.*;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TextPrice;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.EmailReplyText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Вид экрана «Ответ поставщика» (спека §4.1): позиции запроса с ценой, 💡 и строкой файла рядом, расхождения,
 * подсказки для позиций без цены (сопоставление считается заново из таблицы выбранного файла), источники (файлы и
 * письма), таблица выбранного файла, счётчики. Выбранный файл: присланный fileId этого запроса → файл, к строкам
 * которого привязано больше всего цен → последний разобранный → последний PDF / картинка → нет. Файл в чужой валюте —
 * блок курса (вписанный курс, наш курс и курс сегодня; подсказка — наш курс, иначе последний курс этой валюты на рынке),
 * у строк — цена, пересчитанная по курсу; у позиции в чужой валюте — исходная цена и она же по курсу сегодня.
 */
@Service
public class SupplierReplyViewBuilder {

    private final SupplierReplyService service;
    private final PriceRequestItemRepository items;
    private final SupplierReplyFileRepository files;
    private final InboundEmailRepository inbound;
    private final CurrencyRates rates;

    public SupplierReplyViewBuilder(SupplierReplyService service, PriceRequestItemRepository items,
                                    SupplierReplyFileRepository files, InboundEmailRepository inbound, CurrencyRates rates) {
        this.service = service;
        this.items = items;
        this.files = files;
        this.inbound = inbound;
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public SupplierReplyView view(Long prId, Long fileId) {
        PriceRequest pr = service.guard(prId);
        List<PriceRequestItem> its = new ArrayList<>(items.findByPriceRequestId(prId));
        its.sort(Comparator.comparing(PriceRequestItem::getId));
        List<ReplyFileInfo> infos = files.findInfo(prId);
        Long selected = select(fileId, its, infos);
        ReplyTable table = selected == null ? null : files.findParsed(selected).orElse(null);
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();

        List<ReplyRowValues> values = table == null ? List.of() : ReplyRowValues.of(table);
        String cur = table == null ? null : SupplierReplyService.tableCurrency(values, table);
        ReplyFileInfo info = selected == null ? null : infos.stream().filter(f -> f.id().equals(selected)).findFirst().orElse(null);
        SupplierReplyService.FileRate rate = info == null ? null
                : SupplierReplyService.fileRate(info.rate(), info.rateCurrency(), cur, market);
        Map<Integer, ReplyRowValues> byRow = new HashMap<>();
        List<ReplyMatcher.Row> mrows = new ArrayList<>();
        int order = 0;
        for (ReplyRowValues r : values) {
            byRow.put(r.fileRow(), r);
            if (r.participates()) mrows.add(new ReplyMatcher.Row(r.fileRow(), order++, r.matchText(), r.quantity() == null ? null : r.quantity().value()));
        }
        List<ReplyMatcher.Position> pos = new ArrayList<>();
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            pos.add(new ReplyMatcher.Position(it.getId(), i + 1, SupplierReplyService.positionText(it), it.getRequestedQuantity()));
        }
        Map<Long, ReplyMatcher.Match> matches = new HashMap<>();
        for (ReplyMatcher.Match m : ReplyMatcher.match(pos, mrows)) matches.put(m.itemId(), m);

        List<Position> positions = new ArrayList<>();
        Set<Integer> usedRows = new HashSet<>();
        Map<Integer, Integer> rowOrdinal = new HashMap<>();
        int unverified = 0, noPrice = 0;
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            TenderLot l = it.getTenderLot();
            MedEquipment e = it.getMedEquipment();
            if (it.isResponseAuto()) unverified++;
            if (it.getResponsePrice() == null) noPrice++;
            RowRef row = null, suggestion = null;
            List<String> flags = new ArrayList<>();
            if (selected != null && selected.equals(it.getResponseFileId()) && it.getResponseFileRow() != null) {
                ReplyRowValues r = byRow.get(it.getResponseFileRow());
                if (r != null) {
                    double score = ReplyMatcher.score(pos.get(i).text(), r.matchText(), mrows);
                    row = ref(r, score, market, rate);
                    usedRows.add(r.fileRow());
                    rowOrdinal.put(r.fileRow(), i + 1);
                    flags = flags(it, l, r, score, market, rate);
                }
            } else if (it.getResponsePrice() == null && matches.containsKey(it.getId())) {
                // строка файла для позиции без цены — и с ценой («Подставить»), и без («нет в наличии»: отказ виден
                // на экране, отдельным состоянием не хранится — спека §10)
                ReplyMatcher.Match m = matches.get(it.getId());
                ReplyRowValues r = byRow.get(m.fileRow());
                if (r != null) {
                    suggestion = ref(r, m.score(), market, rate);
                    usedRows.add(r.fileRow());
                }
            }
            BigDecimal todayPrice = null;
            if (it.getResponseCurrency() != null && it.getResponseOriginal() != null) {
                todayPrice = rates.today(pr.getMarket(), it.getResponseCurrency())
                        .map(td -> RateMath.convert(it.getResponseOriginal(), td.rate())).orElse(null);
            }
            positions.add(new Position(it.getId(), i + 1, l == null ? "" : l.getEquipName(), l == null ? null : l.getManufact(),
                    e == null ? null : e.getName(), it.getRequestedQuantity(), l == null ? null : l.getUnit(),
                    it.getResponsePrice(), it.isResponseAuto(), it.getResponseNote(), it.getResponseFileId(),
                    it.getResponseFileRow(), row, suggestion, flags, it.getResponseCurrency(), it.getResponseOriginal(),
                    it.getResponseRate(), todayPrice));
        }
        int unassigned = 0;
        for (ReplyRowValues r : values) if (r.price() != null && !usedRows.contains(r.fileRow())) unassigned++;

        Table tbl = null;
        if (table != null) {
            List<Col> cols = table.columns().stream().map(c -> new Col(c.index(), c.header(), c.role())).toList();
            List<TRow> rows = new ArrayList<>();
            for (ReplyTable.Row r : table.rows()) {
                ReplyRowValues v = byRow.get(r.fileRow());
                rows.add(new TRow(r.fileRow(), r.cells(), rowOrdinal.get(r.fileRow()), v != null && v.price() != null,
                        v == null ? "" : v.displayText(), v == null ? null : v.price(), v == null ? null : v.currency(),
                        v == null ? null : converted(v, market, rate)));
            }
            List<Total> totals = table.totals() == null ? List.of()
                    : table.totals().stream().map(x -> new Total(x.label(), x.amount())).toList();
            tbl = new Table(table.source(), table.headerRow(), table.currency(), origin(table), cols, rows, totals,
                    discount(table.totals(), values));
        }
        Tender t = pr.getTender();
        Head head = new Head(pr.getId(), pr.getStatus(), pr.getDistributor() == null ? "поставщик" : pr.getDistributor().getName(),
                t.getId(), t.getTenderNumber(), t.getSource() == com.vladoose.nir.entity.Source.PRIVATE_REQUEST, pr.getSentAt(),
                pr.getResponseDate(), market);
        Rate rateView = null;
        // «валюта не ясна» — без поля курса: пересчитывать нечего, сначала валюту выбирают над таблицей файла
        if (table != null && cur != null && market != null && !cur.equals(market) && !MoneyText.MIXED.equals(cur)) {
            BigDecimal our = rates.our(pr.getMarket(), cur).orElse(null);
            CurrencyRates.Today td = rates.today(pr.getMarket(), cur).orElse(null);
            List<Object[]> last = our != null ? List.of() : files.findLastRate(pr.getMarket().name(), cur);
            rateView = new Rate(cur, rate == null ? null : rate.rate(),
                    our != null ? our : last.isEmpty() ? null : (BigDecimal) last.get(0)[0],
                    our != null || last.isEmpty() ? null : at(last.get(0)[1]),
                    our, td == null ? null : td.rate(), td == null ? null : td.date());
        }
        // цены в тексте буклета — подсказка, когда позиция одна (у многих «Стоимость 1 390 000 тенге» не скажет, чья)
        List<TextPrice> textPrices = selected == null || table != null || its.size() != 1 ? List.of()
                : files.findTextPrices(selected);
        return new SupplierReplyView(head, positions, sources(prId, infos), selected, tbl, new Counts(unverified, noPrice, unassigned),
                rateView, textPrices);
    }

    /** Откуда валюта цен файла — для строки над таблицей (у таблиц до 2026-10-18 источник выводится из валюты). */
    static CurrencyOrigin origin(ReplyTable t) {
        ReplyTable.CurrencyFrom f = t.currencyFrom();
        List<String> texts = f == null || f.texts() == null ? List.of() : f.texts();
        OffsetDateTime at = null;
        if (f != null && f.at() != null) {
            try {
                at = OffsetDateTime.parse(f.at());
            } catch (java.time.format.DateTimeParseException e) {
                at = null;
            }
        }
        return new CurrencyOrigin(t.currencySource().name(), texts, f == null ? null : f.by(), at);
    }

    private static Long select(Long requested, List<PriceRequestItem> its, List<ReplyFileInfo> infos) {
        if (requested != null && infos.stream().anyMatch(f -> f.id().equals(requested))) return requested;
        Map<Long, Long> linked = new HashMap<>();
        for (PriceRequestItem it : its) if (it.getResponseFileId() != null) linked.merge(it.getResponseFileId(), 1L, Long::sum);
        Optional<Long> most = linked.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        if (most.isPresent() && infos.stream().anyMatch(f -> f.id().equals(most.get()))) return most.get();
        for (int i = infos.size() - 1; i >= 0; i--) if (infos.get(i).parseStatus() == ReplyParseStatus.PARSED) return infos.get(i).id();
        // файл без таблицы — тоже документ: Word-буклет (цены в тексте — подсказка), PDF, картинка
        for (int i = infos.size() - 1; i >= 0; i--) {
            ReplyFileKind k = infos.get(i).kind();
            if (k == ReplyFileKind.PDF || k == ReplyFileKind.IMAGE || k == ReplyFileKind.WORD) return infos.get(i).id();
        }
        return null;
    }

    private List<Source> sources(Long prId, List<ReplyFileInfo> infos) {
        List<Source> out = new ArrayList<>();
        for (ReplyFileInfo f : infos) {
            out.add(new Source("FILE", f.id(), f.inboundEmailId(), f.fileName(), f.kind().name(), f.parseStatus().name(),
                    f.parseNote(), f.sizeBytes(), f.pageCount(), f.uploadedBy() != null, f.createdAt(), null, null, null));
        }
        for (Object[] r : inbound.findSupplierReplies(prId)) {
            String text = r[4] == null ? null : EmailReplyText.cutQuote((String) r[4]);
            out.add(new Source("EMAIL", null, ((Number) r[0]).longValue(), "Письмо", null, null, null, null, null, false,
                    at(r[1]), (String) r[2], (String) r[3], text));
        }
        return out;
    }

    private static OffsetDateTime at(Object v) {
        if (v instanceof OffsetDateTime o) return o;
        if (v instanceof Instant i) return i.atOffset(ZoneOffset.UTC);
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    /**
     * Скидка только в итоге файла (КП «Рикты»: цены строк без скидки, «ВСЕГО : С учетом скидки на основное оборудование
     * 12%» ниже «ИТОГО»): итоговая строка со «скидк…» в подписи меньше суммы строк (сумма строки, иначе цена × кол-во)
     * больше чем на 0,5 % — её и сумму строк покажет экран. Прочие итоги (НДС, «без НДС») — только под таблицей: их
     * расхождение с суммой строк скидки не значит.
     */
    static Discount discount(List<ReplyTable.Total> totals, List<ReplyRowValues> values) {
        if (totals == null || totals.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (ReplyRowValues r : values) {
            BigDecimal s = r.sum() != null ? r.sum()
                    : r.price() != null && r.quantity() != null ? r.price().multiply(r.quantity().value()) : null;
            if (s == null) continue;
            sum = sum.add(s);
            any = true;
        }
        if (!any) return null;
        BigDecimal tolerance = sum.multiply(new BigDecimal("0.005")).max(BigDecimal.ONE);
        for (ReplyTable.Total t : totals) {
            if (t.label() == null || t.amount() == null || !t.label().toLowerCase(Locale.ROOT).contains("скидк")) continue;
            if (sum.subtract(t.amount()).compareTo(tolerance) > 0) {
                return new Discount(t.label(), t.amount(), sum.setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        return null;
    }

    private static RowRef ref(ReplyRowValues r, double score, String market, SupplierReplyService.FileRate rate) {
        return new RowRef(r.fileRow(), r.displayText(), r.quantity() == null ? null : r.quantity().value(),
                r.unit(), r.price(), r.priceComputed(), r.note(), score, r.currency(), converted(r, market, rate));
    }

    /** Цена строки, пересчитанная по курсу файла в валюту запроса; курса нет или валюта не та — null. */
    private static BigDecimal converted(ReplyRowValues r, String market, SupplierReplyService.FileRate rate) {
        return SupplierReplyService.converted(r, market, rate) ? SupplierReplyService.marketPrice(r, market, rate) : null;
    }

    /**
     * Расхождения строки файла с позицией (спека §4.1). Строка в валюте курса: цена позиции — пересчёт строки — не
     * расхождение (пересчёт и так в строке файла: «60 000 тг = 10 000 руб. × 6»); не равна ему (поправили руками) —
     * «цена не по курсу 6: по строке файла — 60 000,00 тг»; курса нет — «цена файла в руб.» («в юанях»); валюта не ясна
     * (в тексте документа — разные валюты или суммы в тысячах) — так и сказано, с подсказкой выбрать валюту.
     */
    static List<String> flags(PriceRequestItem it, TenderLot l, ReplyRowValues r, double score, String market,
                              SupplierReplyService.FileRate rate) {
        List<String> out = new ArrayList<>();
        Integer q = it.getRequestedQuantity();
        String unit = l == null || l.getUnit() == null || l.getUnit().isBlank() ? "шт" : l.getUnit();
        if (q != null && r.quantity() != null && r.quantity().value().compareTo(BigDecimal.valueOf(q)) != 0) {
            out.add("кол-во: " + r.quantity().value().toPlainString() + (r.unit() == null ? "" : " " + r.unit()) + " ≠ " + q + " " + unit);
        } else if (r.unit() != null && !sameUnit(r.unit(), unit)) {
            out.add("ед.: " + r.unit() + " ≠ " + unit);
        }
        if (r.price() != null && r.sum() != null && r.quantity() != null && !r.priceComputed()) {
            BigDecimal diff = r.price().multiply(r.quantity().value()).subtract(r.sum()).abs();
            BigDecimal tol = r.sum().multiply(new BigDecimal("0.005")).max(BigDecimal.ONE);
            if (diff.compareTo(tol) > 0) out.add("сумма ≠ цена × кол-во");
        }
        if (MoneyText.MIXED.equals(r.currency())) {
            out.add("валюта цены не ясна — укажите валюту цен файла");
        } else if (r.currency() != null && market != null && !r.currency().equals(market)) {
            BigDecimal conv = SupplierReplyService.converted(r, market, rate) ? SupplierReplyService.marketPrice(r, market, rate) : null;
            if (conv == null) {
                out.add("цена файла в " + MoneyText.label(r.currency()));
            } else if (it.getResponsePrice() != null && it.getResponsePrice().compareTo(conv) != 0) {
                out.add("цена не по курсу " + SupplierReplyService.rateText(rate.rate()) + ": по строке файла — "
                        + DocFormat.money(conv) + " " + MoneyText.label(market));
            }
        }
        if (r.priceComputed()) out.add("цена посчитана из суммы");
        if (score < ReplyMatcher.AUTO_MIN) out.add("названия похожи слабо");
        return out;
    }

    private static boolean sameUnit(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT).replace(".", ""), y = b.toLowerCase(Locale.ROOT).replace(".", "");
        return x.startsWith(y) || y.startsWith(x);
    }
}
