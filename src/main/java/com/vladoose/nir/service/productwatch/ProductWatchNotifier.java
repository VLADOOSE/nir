package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.market.MarketLines;
import com.vladoose.nir.util.ErrorText;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «Наш товар в тендерах» в Telegram (спека 2026-10-19-product-watch §9): зовётся в конце поиска по стране и импортов
 * ЗКО / СК-Фармации (после «🏥 Новый тендер», который сам сообщает о наших товарах в новых тендерах). Кого — тендеры
 * с отметкой выше сообщённой, статус ACTIVE и приём ещё идёт (по часам рынка). Чтение — короткой транзакцией, отправка —
 * вне её; не ушло — признак не ставится, следующий прогон повторит (признак в базе — рестарт его не теряет).
 * С 2026-10-20 (решение оператора) — своя тема и {@link ProductNotifyPolicy}: по умолчанию только «★» и только если наши
 * лоты тендера — от 1 млн ₸; не прошедшие сообщёнными не отмечаются (станут «★» — придут полной карточкой).
 */
@Component
public class ProductWatchNotifier {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchNotifier.class);

    /** Отправка готового текста; в работе — тема нашей продукции Bot API (пусто — тема тендеров). */
    public interface Sender {
        void send(String text, boolean silent);
    }

    /** Сообщение-пачка с кнопками бота (спека 2026-10-21-tender-telegram-bot §4); false — не ушло, повтор следующим прогоном. */
    public interface DigestPublisher {
        boolean publish(ProductDigestComposer.Chunk chunk);
    }

    private final TenderLotProductMatchRepository matches;
    private final TenderRepository tenders;
    private final ProductMatchQuery query;
    private final ProductMatchService matchService;
    private final TransactionTemplate readTx;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final ProductNotifyPolicy policy;
    private final String publicUrl;
    private final Clock clock;
    /** Кнопки бота включены (TELEGRAM_BOT_ACTIONS_ENABLED) — пачка вместо прежнего сообщения; null — прежний вид. */
    private final DigestPublisher digest;
    private MarketLines marketLines;

    @Autowired
    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                TelegramClient client, @Value("${product-watch.enabled:false}") boolean enabled,
                                @Value("${product-watch.notify:}") String notify,
                                @Value("${product-watch.notify-min-sum:}") String notifyMinSum,
                                @Value("${ais.public-url:}") String publicUrl,
                                DigestPublisher digestPublisher,
                                @Value("${notify.telegram.bot-actions-enabled:false}") boolean botActions) {
        this(matches, tenders, query, matchService, tm, telegram, client::sendProducts, enabled,
                ProductNotifyPolicy.of(notify, notifyMinSum), publicUrl, Clock.systemUTC(), botActions ? digestPublisher : null);
    }

    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                Sender sender, boolean enabled, ProductNotifyPolicy policy, String publicUrl, Clock clock) {
        this(matches, tenders, query, matchService, tm, telegram, sender, enabled, policy, publicUrl, clock, null);
    }

    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                Sender sender, boolean enabled, ProductNotifyPolicy policy, String publicUrl, Clock clock,
                                DigestPublisher digest) {
        this.matches = matches;
        this.tenders = tenders;
        this.query = query;
        this.matchService = matchService;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.policy = policy;
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.digest = digest;
    }

    /** Что и куда шлём — в лог при старте: на проде иначе не видно, какой режим и тема подхватились. */
    @PostConstruct
    void logPolicy() {
        if (!enabled) return;
        String what = switch (policy.mode()) {
            case ALL -> "все отметки";
            case STAR -> "только «★»";
            case OFF -> "выключено";
        };
        String min = policy.minSum().signum() == 0 ? "без порога суммы" : "наши лоты от " + policy.minSum().toPlainString() + " ₸";
        String topic = !telegram.productsThreadId().isEmpty() ? "тема " + telegram.productsThreadId()
                : telegram.tendersThreadId().isEmpty() ? "«Общая»" : "тема тендеров " + telegram.tendersThreadId();
        log.info("«Наш товар» в Telegram: {}, {}, {}{}", what, min, topic,
                digest != null ? ", сообщение-пачка с кнопками бота" : "");
    }

    /**
     * Ничего не бросает: сбой уведомления не роняет итог прогона, он виден в итоге ({@code addSideError}).
     * synchronized — поиск и импорты заканчивают в разных потоках.
     */
    public synchronized void afterRun(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured() || policy.mode() == ProductNotifyPolicy.Mode.OFF) return;
        Prepared prepared;
        try {
            prepared = readTx.execute(st -> prepare());
        } catch (RuntimeException e) {
            log.warn("Telegram: «наш товар в тендерах» не собрано, повтор — со следующим прогоном: {}", ErrorText.of(e));
            sum.addSideError("уведомление «наш товар» не собрано (повтор — со следующим прогоном): " + ErrorText.of(e));
            return;
        }
        if (prepared == null) return;
        List<ProductWatchComposer.Card> cards = prepared.cards();
        if (cards.isEmpty()) return;
        if (digest != null) {
            publishDigest(cards, sum);
            return;
        }
        ProductWatchComposer.Message m = ProductWatchComposer.compose(cards, publicUrl);
        try {
            sender.send(m.text(), !m.sound());
        } catch (RuntimeException e) {
            log.warn("Telegram: «наш товар в тендерах» не ушло ({}), повтор — со следующим прогоном: {}", cards.size(), ErrorText.of(e));
            sum.addSideError("уведомление «наш товар» не ушло (повтор — со следующим прогоном): " + ErrorText.of(e));
            return;
        }
        Set<Long> shown = new HashSet<>(m.shown());
        for (ProductWatchComposer.Card c : cards) {
            if (!shown.contains(c.tenderId())) continue;   // не поместился — уйдёт следующим прогоном
            markQuietly(c.tenderId(), c.level());
        }
        log.info("Telegram: «наш товар в тендерах» отправлено ({} из {}){}", shown.size(), cards.size(), m.sound() ? " со звуком" : "");
    }

    /**
     * Сообщение-пачка (спека 2026-10-21-tender-telegram-bot §4): по 15 тендеров; сообщёнными отмечаются вошедшие в ушедшую
     * пачку; не ушла — остальные ждут следующего прогона.
     */
    private void publishDigest(List<ProductWatchComposer.Card> cards, ImportSummary sum) {
        Map<Long, String> market = Map.of();
        if (marketLines != null) {
            Map<Long, List<Long>> lotIds = new LinkedHashMap<>();
            for (ProductWatchComposer.Card c : cards) lotIds.put(c.tenderId(), c.lots().stream().map(ProductMatchQuery.LotLine::lotId).toList());
            try {
                market = marketLines.lines(lotIds);
            } catch (RuntimeException e) {
                log.warn("«Наш товар»: строки цены рынка не собраны: {}", ErrorText.of(e));
            }
        }
        Map<Long, ProductWatchComposer.Card> byId = cards.stream()
                .collect(Collectors.toMap(ProductWatchComposer.Card::tenderId, c -> c, (a, b) -> a));
        int sent = 0;
        for (ProductDigestComposer.Chunk chunk : ProductDigestComposer.compose(cards, publicUrl, market)) {
            if (!digest.publish(chunk)) {
                sum.addSideError("уведомление «наш товар» с кнопками не ушло (повтор — со следующим прогоном)");
                break;
            }
            chunk.tenderIds().forEach(id -> markQuietly(id, byId.get(id).level()));
            sent += chunk.tenderIds().size();
        }
        log.info("Telegram: «наш товар» — сообщение-пачка с кнопками, тендеров {} из {}", sent, cards.size());
    }

    /** Строка «рынок: …» в пачке (спека 2026-10-22-tender-results-market-prices §10); нет бина (тесты) — без строки. */
    @Autowired(required = false)
    public void setMarketLines(MarketLines marketLines) {
        this.marketLines = marketLines;
    }

    /** Ушло, но не отмечено — следующий прогон пришлёт ещё раз (лучше повтор, чем потеря). */
    private void markQuietly(long tenderId, ProductMatchLevel level) {
        try {
            matchService.markNotified(tenderId, level);
        } catch (RuntimeException e) {
            log.warn("«Наш товар»: тендер {} не отмечен сообщённым: {}", tenderId, ErrorText.of(e));
        }
    }

    /** Карточки сообщения. */
    private record Prepared(List<ProductWatchComposer.Card> cards) {}

    private Prepared prepare() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        List<Long> ids = matches.findTendersToNotify();
        if (ids.isEmpty()) return new Prepared(List.of());
        Map<Long, ProductMatchQuery.Summary> sums = query.summaries(ids);
        List<ProductWatchComposer.Card> out = new ArrayList<>();
        for (Tender t : tenders.findAllById(ids)) {
            if (t.getMarket() != Market.KZ || !"ACTIVE".equals(t.getStatus())) continue;
            // приём «до 09:00» в 09:10 закрыт, даже если площадка ещё не сменила статус (спека §9, Review Focus #3)
            if (ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) continue;
            ProductMatchQuery.Summary s = sums.get(t.getId());
            if (s == null || s.level() == null) continue;
            boolean upgrade = t.getProductNotifiedLevel() != null;
            // «не наше» и «аналог» по ответу ИИ (спека 2026-10-21-tender-telegram-bot §4): решение принято — ни в сообщение, ни в сумму
            Set<Long> notOurs = t.getLots().stream().filter(l -> l.getAiVerdict() == AiVerdict.NO || l.getAiVerdict() == AiVerdict.ANALOG)
                    .map(TenderLot::getId)
                    .collect(Collectors.toSet());
            // «Уточнение» и режим «только ★» — только лоты «★» и «✓ подходит»; порог — по сумме тех лотов, о которых сообщение
            List<ProductMatchQuery.LotLine> lots = (upgrade || policy.mode() == ProductNotifyPolicy.Mode.STAR
                    ? s.lots().stream().filter(l -> l.level().alert()).toList()
                    : s.lots()).stream().filter(l -> !notOurs.contains(l.lotId())).toList();
            if (lots.isEmpty()) continue;
            BigDecimal ourSum = OurLots.sum(t, lots.stream().map(ProductMatchQuery.LotLine::lotId).toList());
            if (!policy.sumPasses(ourSum)) continue;
            // значок, звук и «сообщено» — по лотам сообщения: ★-лот «не наше» не делает тендер «★» со звуком
            ProductMatchLevel level = lots.stream().map(ProductMatchQuery.LotLine::level).reduce(null, ProductMatchLevel::max);
            out.add(new ProductWatchComposer.Card(t.getId(), t.getTenderNumber(), platformLabel(t.getPlatform()),
                    t.getPurchaseType(), t.getDescription(), t.getCustomerName(), t.getRegion(), t.getTotalCost(),
                    t.getDeadline(), t.getDeadlineAt(), level, upgrade, lots, ourSum));
        }
        // сначала точнее, внутри — у кого приём кончается раньше
        out.sort(Comparator.comparing((ProductWatchComposer.Card c) -> -c.level().ordinal())
                .thenComparing(c -> c.deadlineAt() != null ? c.deadlineAt()
                        : c.deadline() != null ? c.deadline().atTime(23, 59) : LocalDateTime.MAX)
                .thenComparing(ProductWatchComposer.Card::tenderId));
        return new Prepared(out);
    }

    private static String platformLabel(TenderPlatform p) {
        return p == TenderPlatform.SK_PHARMACY ? "СК-Фармация" : "goszakup";
    }
}
