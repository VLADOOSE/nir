package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LotResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Применение блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6) — общее для бота и «✍️ Вставить ответ ИИ» в АИС:
 * вывод ИИ — у лота всегда; «идти» + наш товар из кандидатов лота — модель в работе (ручную не трогает); письма — заводу
 * бренда модели или «Доверенным», если этому поставщику по лоту запрос ещё не уходил.
 */
@Service
public class AiAnswerService {

    public static final int MAX_TEXT = 20_000;
    /** Причина в строке итога (Telegram и окно АИС) — не длиннее; полная (≤ 500) — у лота. */
    static final int REASON_SHOWN = 150;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final TenderLotRepository lots;
    private final TenderLotProductMatchRepository matches;
    private final SiteProductRepository products;
    private final LotModelService models;
    private final DistributorService distributors;
    private final PriceRequestItemRepository items;

    public AiAnswerService(TenderLotRepository lots, TenderLotProductMatchRepository matches, SiteProductRepository products,
                           LotModelService models, DistributorService distributors, PriceRequestItemRepository items) {
        this.lots = lots;
        this.matches = matches;
        this.products = products;
        this.models = models;
        this.distributors = distributors;
        this.items = items;
    }

    @Transactional
    public AiAnswerResult apply(String text) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (text == null || text.isBlank()) throw new BadRequestException("Вставьте ответ ИИ — блок «Для АИС»");
        if (text.length() > MAX_TEXT) throw new BadRequestException("Ответ длиннее 20 000 знаков — вставьте только блок «Для АИС»");
        AiAnswerParser.Parsed parsed = AiAnswerParser.parse(text);
        LocalDateTime now = LocalDateTime.now(Market.KZ.zone());
        OffsetDateTime stamp = OffsetDateTime.now();
        String by = CurrentUser.label();
        List<LotResult> results = new ArrayList<>();
        List<TenderLot> toLetter = new ArrayList<>();
        for (AiAnswerParser.Line line : parsed.lines()) {
            TenderLot lot = lots.findById(line.lotId()).orElse(null);
            if (lot == null || lot.getTender().getMarket() != Market.KZ || lot.getTender().getSource() != Source.PUBLIC_TENDER) {
                results.add(new LotResult(line.lotId(), null, null, null, null, line.verdict(), Outcome.LOT_NOT_FOUND, null,
                        "⚠ лот L" + line.lotId() + " не найден"));
                continue;
            }
            Tender t = lot.getTender();
            SiteProduct product = null;
            Outcome rejected = null;
            if (line.productId() != null) {
                if (!candidates(lot.getId()).contains(line.productId())) {
                    rejected = Outcome.PRODUCT_NOT_CANDIDATE;
                } else {
                    product = products.findById(line.productId()).orElse(null);
                    if (product == null || product.getRemovedAt() != null) {
                        rejected = Outcome.PRODUCT_REMOVED;
                        product = null;
                    }
                }
            }
            lot.setAiVerdict(line.verdict());
            lot.setAiProduct(product);
            lot.setAiNote(line.reason());
            lot.setAiBy(by);
            lot.setAiAt(stamp);
            boolean closed = !"ACTIVE".equals(t.getStatus()) || ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now);
            String model = product == null ? null : ProductMatchQuery.shortName(product.getName());
            Outcome o;
            if (line.verdict() == AiVerdict.NO) o = Outcome.NOT_OURS;
            else if (line.verdict() == AiVerdict.UNSURE) o = Outcome.UNSURE;
            else if (line.verdict() == AiVerdict.ANALOG) o = Outcome.ANALOG;   // наш не подходит — модель не ставим, писем нет
            else if (rejected != null) o = rejected;
            else if (product == null) o = Outcome.ADVICE;
            else if (closed) o = Outcome.CLOSED;
            else o = pin(lot, product, line.reason());
            if (o == Outcome.PINNED || o == Outcome.SAME_MODEL) toLetter.add(lot);
            results.add(new LotResult(lot.getId(), t.getId(), t.getTenderNumber(), lot.getEquipName(), t.getCustomerName(),
                    line.verdict(), o, model, text(o, lot, line, model)));
        }
        List<String> notes = new ArrayList<>();
        List<LetterPlan> letters = plan(toLetter, notes);
        return new AiAnswerResult(results, parsed.unparsed(), letters, notes);
    }

    /** Наши товары, что были у лота в тексте для ИИ: товар отметки и кандидаты сверки (как ProductAiBrief). */
    private Set<Long> candidates(long lotId) {
        Set<Long> ids = new HashSet<>();
        for (TenderLotProductMatch m : matches.findByLotId(lotId)) {
            if (m.getProductId() != null) ids.add(m.getProductId());
            if (m.getFitCandidates() != null) m.getFitCandidates().forEach(c -> ids.add(c.productId()));
        }
        return ids;
    }

    private Outcome pin(TenderLot lot, SiteProduct product, String reason) {
        MedEquipment cur = lot.getProposedEquipment();
        if (cur != null && cur.getSiteProduct() != null && cur.getSiteProduct().getId().equals(product.getId())) return Outcome.SAME_MODEL;
        if (cur != null && !lot.isModelByAi()) return Outcome.KEPT_MANUAL;
        models.applyByAi(lot, models.equipmentForSiteProduct(product), reason == null ? "ИИ" : "ИИ: " + reason);
        return Outcome.PINNED;
    }

    private List<LetterPlan> plan(List<TenderLot> toLetter, List<String> notes) {
        if (toLetter.isEmpty()) return List.of();
        List<Distributor> all = distributors.findAll();   // рынок режет аспект
        Map<String, Acc> groups = new LinkedHashMap<>();
        for (TenderLot lot : toLetter) {
            String brand = lot.getProposedEquipment() == null ? null : lot.getProposedEquipment().getManufact();
            List<Distributor> to = LetterRecipients.pick(all, brand);
            if (to.isEmpty()) {
                notes.add("«" + lotName(lot) + "» — письмо из АИС: нет завода бренда «" + Objects.toString(brand, "—") + "» с почтой");
                continue;
            }
            for (Distributor d : to) {
                OffsetDateTime last = items.lastRequestedAt(lot.getId(), d.getId());
                if (last != null) {
                    notes.add("«" + lotName(lot) + "» — у " + d.getName() + " уже запрошено "
                            + last.atZoneSameInstant(Market.KZ.zone()).format(DAY));
                    continue;
                }
                groups.computeIfAbsent(lot.getTender().getId() + ":" + d.getId(), k -> new Acc(lot.getTender(), d)).add(lot);
            }
        }
        return groups.values().stream().map(Acc::plan).toList();
    }

    private static final class Acc {
        final Tender tender;
        final Distributor d;
        final List<Long> lotIds = new ArrayList<>();
        final List<String> lotNames = new ArrayList<>();

        Acc(Tender tender, Distributor d) { this.tender = tender; this.d = d; }

        void add(TenderLot l) { lotIds.add(l.getId()); lotNames.add(lotName(l)); }

        LetterPlan plan() {
            return new LetterPlan(tender.getId(), tender.getTenderNumber(), tender.getCustomerName(), d.getId(), d.getName(),
                    d.isManufacturer(), List.copyOf(lotIds), List.copyOf(lotNames));
        }
    }

    private static String text(Outcome o, TenderLot lot, AiAnswerParser.Line line, String model) {
        String head = "«" + lotName(lot) + "»" + who(lot.getTender());
        String why = line.reason() == null ? "" : TelegramText.oneLine(line.reason(), REASON_SHOWN);   // полная — у лота (ai_note)
        return switch (o) {
            case PINNED -> "✅ " + head + " → " + model + " — закреплено";
            case SAME_MODEL -> "✅ " + head + " → " + model + " — уже в работе";
            case KEPT_MANUAL -> "⚠ " + head + " — у лота уже " + current(lot) + ", ИИ советует " + model + " — замените в АИС";
            case ADVICE -> "💬 " + head + " — ИИ советует не наш товар" + (why.isEmpty() ? "" : ": " + why) + "; закрепить можно в АИС";
            case ANALOG -> "🔁 " + head + " — наше не подходит, можно аналогом" + (why.isEmpty() ? "" : ": " + why);
            case NOT_OURS -> "✕ " + head + " — не наше" + (why.isEmpty() ? "" : ": " + why);
            case UNSURE -> "❔ " + head + " — не ясно" + (why.isEmpty() ? "" : ": " + why);
            case CLOSED -> "⏹ " + head + " — приём закрыт, модель не закреплена";
            case PRODUCT_NOT_CANDIDATE -> "⚠ " + head + " — товар P" + line.productId() + " не из кандидатов лота — не закрепляю";
            case PRODUCT_REMOVED -> "⚠ " + head + " — товар P" + line.productId() + " снят с сайта — не закрепляю";
            case LOT_NOT_FOUND -> "⚠ лот L" + line.lotId() + " не найден";
        };
    }

    private static String who(Tender t) {
        String c = t.getCustomerName();
        return c == null || c.isBlank() ? "" : " · " + TelegramText.oneLine(c, 60);
    }

    private static String current(TenderLot lot) {
        MedEquipment e = lot.getProposedEquipment();
        String by = lot.getModelSetBy();
        return ProductMatchQuery.shortName(e.getName()) + (by == null ? "" : " (" + by + ")");
    }

    private static String lotName(TenderLot l) {
        return TelegramText.oneLine(Objects.toString(l.getEquipName(), "без названия"), 80);
    }
}
