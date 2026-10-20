package com.vladoose.nir.service.productwatch;

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

    @Autowired
    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                TelegramClient client, @Value("${product-watch.enabled:false}") boolean enabled,
                                @Value("${product-watch.notify:}") String notify,
                                @Value("${product-watch.notify-min-sum:}") String notifyMinSum,
                                @Value("${ais.public-url:}") String publicUrl) {
        this(matches, tenders, query, matchService, tm, telegram, client::sendProducts, enabled,
                ProductNotifyPolicy.of(notify, notifyMinSum), publicUrl, Clock.systemUTC());
    }

    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                Sender sender, boolean enabled, ProductNotifyPolicy policy, String publicUrl, Clock clock) {
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
        log.info("«Наш товар» в Telegram: {}, {}, {}", what, min, topic);
    }

    /**
     * Ничего не бросает: сбой уведомления не роняет итог прогона, он виден в итоге ({@code addSideError}).
     * synchronized — поиск и импорты заканчивают в разных потоках.
     */
    public synchronized void afterRun(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured() || policy.mode() == ProductNotifyPolicy.Mode.OFF) return;
        List<ProductWatchComposer.Card> cards;
        try {
            cards = readTx.execute(st -> prepare());
        } catch (RuntimeException e) {
            log.warn("Telegram: «наш товар в тендерах» не собрано, повтор — со следующим прогоном: {}", ErrorText.of(e));
            sum.addSideError("уведомление «наш товар» не собрано (повтор — со следующим прогоном): " + ErrorText.of(e));
            return;
        }
        if (cards == null || cards.isEmpty()) return;
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
            try {
                matchService.markNotified(c.tenderId(), c.level());
            } catch (RuntimeException e) {
                // ушло, но не отмечено — следующий прогон пришлёт ещё раз (лучше повтор, чем потеря)
                log.warn("«Наш товар»: тендер {} не отмечен сообщённым: {}", c.tenderId(), ErrorText.of(e));
            }
        }
        log.info("Telegram: «наш товар в тендерах» отправлено ({} из {}){}", shown.size(), cards.size(), m.sound() ? " со звуком" : "");
    }

    private List<ProductWatchComposer.Card> prepare() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        List<Long> ids = matches.findTendersToNotify();
        if (ids.isEmpty()) return List.of();
        Map<Long, ProductMatchQuery.Summary> sums = query.summaries(ids);
        List<ProductWatchComposer.Card> out = new ArrayList<>();
        for (Tender t : tenders.findAllById(ids)) {
            if (t.getMarket() != Market.KZ || !"ACTIVE".equals(t.getStatus())) continue;
            // приём «до 09:00» в 09:10 закрыт, даже если площадка ещё не сменила статус (спека §9, Review Focus #3)
            if (ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) continue;
            ProductMatchQuery.Summary s = sums.get(t.getId());
            if (s == null || s.level() == null) continue;
            boolean upgrade = t.getProductNotifiedLevel() != null;
            // «Уточнение» и режим «только ★» — только лоты «★» и «✓ подходит»; порог — по сумме тех лотов, о которых сообщение
            List<ProductMatchQuery.LotLine> lots = upgrade || policy.mode() == ProductNotifyPolicy.Mode.STAR
                    ? s.lots().stream().filter(l -> l.level().alert()).toList()
                    : s.lots();
            if (lots.isEmpty() || !policy.sumPasses(ourLotsSum(t, lots))) continue;
            out.add(new ProductWatchComposer.Card(t.getId(), t.getTenderNumber(), platformLabel(t.getPlatform()),
                    t.getPurchaseType(), t.getDescription(), t.getCustomerName(), t.getRegion(), t.getTotalCost(),
                    t.getDeadline(), t.getDeadlineAt(), s.level(), upgrade, lots));
        }
        // сначала точнее, внутри — у кого приём кончается раньше
        out.sort(Comparator.comparing((ProductWatchComposer.Card c) -> -c.level().ordinal())
                .thenComparing(c -> c.deadlineAt() != null ? c.deadlineAt()
                        : c.deadline() != null ? c.deadline().atTime(23, 59) : LocalDateTime.MAX)
                .thenComparing(ProductWatchComposer.Card::tenderId));
        return out;
    }

    /**
     * Сумма наших лотов: цена за единицу × количество (у goszakup — с V25); цены нет у лота, а он в тендере один —
     * сумма тендера. Ничего не известно — null: порог не доказан, сообщения нет (лучше тишина, чем шлак).
     */
    static BigDecimal ourLotsSum(Tender t, List<ProductMatchQuery.LotLine> lots) {
        Set<Long> ids = new HashSet<>();
        lots.forEach(l -> ids.add(l.lotId()));
        BigDecimal sum = null;
        for (TenderLot l : t.getLots()) {
            if (!ids.contains(l.getId()) || l.getMaxCost() == null || l.getQuantity() == null) continue;
            BigDecimal a = l.getMaxCost().multiply(BigDecimal.valueOf(l.getQuantity()));
            sum = sum == null ? a : sum.add(a);
        }
        if (sum == null && t.getLots().size() == 1 && ids.contains(t.getLots().get(0).getId())) return t.getTotalCost();
        return sum;
    }

    private static String platformLabel(TenderPlatform p) {
        return p == TenderPlatform.SK_PHARMACY ? "СК-Фармация" : "goszakup";
    }
}
