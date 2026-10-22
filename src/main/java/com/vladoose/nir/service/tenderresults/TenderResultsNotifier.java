package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Chunk;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Item;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Итоги в Telegram (спека 2026-10-22-tender-results-market-prices §7): после каждого прохода — «звонить» и наша заявка по темам
 * (наши — со звуком, чужие ЗКО — тихо), в 9:00 — тихая сводка. Чтение — короткой транзакцией, отправка — вне её, отметка — после
 * отправки: не ушло — событие ждёт следующего прохода. Событие старше 3 дней — отмечается без отправки. Включено вместе с
 * уведомлениями о тендерах ({@code tenders.notify.enabled}) и настройками Telegram.
 */
@Component
public class TenderResultsNotifier {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsNotifier.class);
    static final int STALE_DAYS = 3;

    interface Sender {
        void send(ResultTopic topic, String text, boolean silent);
    }

    private final TenderResultEventRepository events;
    private final TenderRepository tenders;
    private final TenderLotRepository lots;
    private final TenderLotResultRepository results;
    private final GoszakupSubjectRepository subjects;
    private final ProductMatchQuery products;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final String publicUrl;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    @Autowired
    public TenderResultsNotifier(TenderResultEventRepository events, TenderRepository tenders, TenderLotRepository lots,
                                 TenderLotResultRepository results, GoszakupSubjectRepository subjects, ProductMatchQuery products,
                                 TelegramSettings telegram, TelegramClient client, PlatformTransactionManager tm,
                                 @Value("${tenders.notify.enabled:false}") boolean enabled, @Value("${ais.public-url:}") String publicUrl) {
        this(events, tenders, lots, results, subjects, products, telegram, (topic, text, silent) -> {
            if (topic == ResultTopic.PRODUCTS) client.sendProducts(text, silent);
            else client.sendTenders(text, silent);
        }, tm, enabled, publicUrl, Clock.systemUTC());
    }

    TenderResultsNotifier(TenderResultEventRepository events, TenderRepository tenders, TenderLotRepository lots,
                          TenderLotResultRepository results, GoszakupSubjectRepository subjects, ProductMatchQuery products,
                          TelegramSettings telegram, Sender sender, PlatformTransactionManager tm, boolean enabled, String publicUrl,
                          Clock clock) {
        this.events = events;
        this.tenders = tenders;
        this.lots = lots;
        this.results = results;
        this.subjects = subjects;
        this.products = products;
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(tm);
    }

    /** «Звонить» и наша заявка — после прохода. Ничего не бросает: сбой — в итог прохода. */
    public synchronized void flushUrgent(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<ResultTopic, List<Item>> byTopic;
        try {
            writeTx.executeWithoutResult(st -> events.markStaleNotified(now.minusDays(STALE_DAYS), now));
            byTopic = readTx.execute(st -> items(events.findUrgentPending()));
        } catch (RuntimeException e) {
            log.warn("Telegram: итоги тендеров не собраны, повтор — следующим проходом: {}", ErrorText.of(e));
            sum.addSideError("итоги в Telegram не собраны (повтор — следующим проходом): " + ErrorText.of(e));
            return;
        }
        for (ResultTopic topic : List.of(ResultTopic.PRODUCTS, ResultTopic.TENDERS)) {
            for (Chunk c : TenderResultsComposer.urgent(byTopic.getOrDefault(topic, List.of()), publicUrl)) {
                if (!send(topic, c, topic == ResultTopic.TENDERS, sum)) break;
                writeTx.executeWithoutResult(st -> events.markNotified(c.eventIds(), now));
            }
        }
    }

    /** Сводка дня — тема нашей продукции, тихо (спека §7). */
    public synchronized void digest(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Item> items;
        try {
            writeTx.executeWithoutResult(st -> events.markStaleDigested(now.minusDays(STALE_DAYS), now));
            items = readTx.execute(st -> items(events.findDigestPending())).getOrDefault(ResultTopic.PRODUCTS, List.of());
        } catch (RuntimeException e) {
            log.warn("Telegram: сводка итогов не собрана: {}", ErrorText.of(e));
            sum.addSideError("сводка итогов не собрана: " + ErrorText.of(e));
            return;
        }
        for (Chunk c : TenderResultsComposer.digest(items, publicUrl)) {
            if (!send(ResultTopic.PRODUCTS, c, true, sum)) break;
            writeTx.executeWithoutResult(st -> events.markDigested(c.eventIds(), now));
        }
    }

    private boolean send(ResultTopic topic, Chunk c, boolean silent, ImportSummary sum) {
        try {
            sender.send(topic, c.text(), silent);
            log.info("Telegram: итоги тендеров — событий {} в тему {}{}", c.eventIds().size(), topic, silent ? "" : " со звуком");
            return true;
        } catch (RuntimeException e) {
            log.warn("Telegram: итоги тендеров не ушли ({}), повтор — следующим проходом: {}", c.eventIds().size(), ErrorText.of(e));
            sum.addSideError("итоги в Telegram не ушли (повтор — следующим проходом): " + ErrorText.of(e));
            return false;
        }
    }

    private Map<ResultTopic, List<Item>> items(List<TenderResultEvent> list) {
        if (list.isEmpty()) return Map.of();
        Map<Long, Tender> tenderById = tenders.findAllById(list.stream().map(TenderResultEvent::getTenderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Tender::getId, Function.identity()));
        Set<Long> lotIds = list.stream().map(TenderResultEvent::getTenderLotId).collect(Collectors.toSet());
        Map<Long, TenderLot> lotById = lots.findAllById(lotIds).stream().collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Map<Long, TenderLotResult> resultByLot = results.findByTenderLotIdIn(lotIds).stream()
                .collect(Collectors.toMap(TenderLotResult::getTenderLotId, Function.identity()));
        Map<String, GoszakupSubject> subjectByBin = subjects.findByBinIn(tenderById.values().stream().map(Tender::getCustomerBin)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(GoszakupSubject::getBin, Function.identity()));
        Map<Long, ProductMatchQuery.Summary> ours = products.summaries(tenderById.keySet());
        Map<ResultTopic, List<Item>> out = new EnumMap<>(ResultTopic.class);
        for (TenderResultEvent e : list) {
            Tender t = tenderById.get(e.getTenderId());
            TenderLot lot = lotById.get(e.getTenderLotId());
            if (t == null || lot == null) continue;
            GoszakupSubject s = t.getCustomerBin() == null ? null : subjectByBin.get(t.getCustomerBin());
            TenderLotResult r = resultByLot.get(lot.getId());
            out.computeIfAbsent(e.getTopic(), k -> new ArrayList<>()).add(new Item(e.getId(), t.getId(), e.getKind(), lot.getEquipName(),
                    lot.getQuantity(), ResultTexts.unit(lot.getPlanUnit(), lot.getUnit()), lot.getMaxCost(),
                    t.getCustomerName() != null ? t.getCustomerName() : s == null ? null : s.getNameRu(), t.getRegion(),
                    s == null ? null : s.getPhone(), s == null ? null : s.getEmail(), e.getText(), r == null ? List.of() : chain(r),
                    oursLine(lot, ours.get(t.getId())), t.getTenderNumber()));
        }
        return out;
    }

    /** Попытки цепочки: «ЗЦП1 — не состоялся → ЗЦП2 — 1 заявка …» без подробностей — номер попытки и итог. */
    static List<String> chain(TenderLotResult r) {
        List<String> out = new ArrayList<>();
        for (ResultAttempt a : r.getAttempts()) {
            String n = a.lotNumber();
            String code = n == null || !n.contains("-") ? (a.method() == null ? "?" : a.method()) : n.substring(n.lastIndexOf('-') + 1);
            out.add(code + " — " + switch (LotOutcome.ofLotStatus(a.statusId())) {
                case DONE -> "состоялся";
                case FAILED -> "ОИ".equals(a.method()) ? "сорвался" : "не состоялся";
                case CANCELLED -> "отменён";
                case PENDING -> "идёт";
            });
        }
        return out;
    }

    /** Модель в работе, иначе наш товар отметки (товар сайта или группа). */
    private static String oursLine(TenderLot lot, ProductMatchQuery.Summary s) {
        if (lot.getProposedEquipment() != null) return "модель в работе — " + lot.getProposedEquipment().getName();
        if (s == null) return null;
        return s.lots().stream().filter(x -> x.lotId() == lot.getId())
                .map(x -> x.productName() != null ? x.productName() : x.groupName()).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
