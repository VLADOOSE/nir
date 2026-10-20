package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.tendernotify.NewTenderComposer.Card;
import com.vladoose.nir.service.tendernotify.NewTenderComposer.LotLine;
import com.vladoose.nir.util.ErrorText;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Уведомление в Telegram (тема «Тендеры» группы «Заявки») о тендерах, СОЗДАННЫХ прогоном импорта: зовётся
 * планировщиками goszakup и СК-Фармации в конце каждого прогона — и автоматического, и по кнопке.
 * <p>
 * В уведомление попадают только действующие тендеры: статус ACTIVE, срок подачи не раньше сегодняшнего дня по
 * Уральску и регион из {@code tenders.notify.regions} (через запятую; пусто — ЗКО, решение оператора «только ЗКО»;
 * все регионы — только явной «*»: пустая переменная окружения не должна молча включать всю страну). У тендеров
 * СК-Фармации регион — организатора (республиканская закупка, «г. Астана»), поэтому при фильтре «ЗКО» они не приходят.
 * <p>
 * Одно сообщение на прогон. Чтение из базы — своей короткой транзакцией, отправка — вне её (§6). Не собралось или
 * не ушло — ошибка в итоге прогона, номера остаются в памяти и уходят со следующим прогоном (рестарт бэкенда их
 * теряет — принято: тендер виден в АИС, а прогоны идут раз в час).
 * <p>
 * Наш товар (спека 2026-10-19-product-watch §9): отметки ставятся до этого уведомления, у тендера с отметкой — строка
 * «★ …» / «🏷 …» под лотами, такие тендеры идут первыми, сообщение с ★ — со звуком; показанные в сообщении тендеры
 * с отметкой считаются сообщёнными — уведомитель нашей продукции второго сообщения о них не шлёт.
 */
@Component
public class NewTenderNotifier {

    private static final Logger log = LoggerFactory.getLogger(NewTenderNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Oral");
    static final String DEFAULT_REGION = "Западно-Казахстанская область";

    /** Отправка готового текста; в работе — тема тендеров Bot API. silent — без звука (со звуком — только ★ «наш товар»). */
    interface Sender {
        void send(String text, boolean silent);
    }

    private final TenderRepository tenderRepository;
    private final TransactionTemplate readTx;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    /** Нормализованные имена регионов; пусто — все регионы (только по явной «*»). */
    private final Set<String> regions;
    private final String publicUrl;
    private final Clock clock;
    private final ProductMatchQuery products;
    private final ProductMatchService productMatches;

    /** Не ушедшие из-за сбоя Telegram или не собранные из-за сбоя базы — уйдут со следующим прогоном (если ещё действуют). */
    private final Set<String> pending = new LinkedHashSet<>();

    @Autowired
    public NewTenderNotifier(TenderRepository tenderRepository, PlatformTransactionManager txManager,
                             TelegramSettings telegram, TelegramClient client, ProductMatchQuery products,
                             ProductMatchService productMatches,
                             @Value("${tenders.notify.enabled:false}") boolean enabled,
                             @Value("${tenders.notify.regions:}") String regions,
                             @Value("${ais.public-url:}") String publicUrl) {
        this(tenderRepository, txManager, telegram, client::sendTenders, products, productMatches, enabled, regions, publicUrl,
                Clock.systemUTC());
    }

    NewTenderNotifier(TenderRepository tenderRepository, PlatformTransactionManager txManager, TelegramSettings telegram,
                      Sender sender, ProductMatchQuery products, ProductMatchService productMatches, boolean enabled,
                      String regions, String publicUrl, Clock clock) {
        this.tenderRepository = tenderRepository;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.regions = parseRegions(regions);
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.products = products;
        this.productMatches = productMatches;
    }

    /** Пусто (в т. ч. пустая переменная окружения) — ЗКО; «*» — все регионы. */
    static Set<String> parseRegions(String csv) {
        Set<String> out = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(NewTenderNotifier::norm).filter(r -> !r.isEmpty()).collect(Collectors.toSet());
        if (out.contains("*")) return Set.of();
        return out.isEmpty() ? Set.of(norm(DEFAULT_REGION)) : out;
    }

    /** Включили, а Telegram не настроен — иначе полная тишина без следа. */
    @PostConstruct
    void logSettings() {
        if (!enabled) return;
        if (!telegram.isConfigured()) {
            log.warn("Уведомления о новых тендерах включены (TENDERS_NOTIFY_ENABLED=true), но Telegram не настроен: нужны "
                    + "TELEGRAM_ENABLED=true, TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID — уведомления не уходят");
        } else {
            log.info("Уведомления о новых тендерах: регионы {}, тема {}", regions.isEmpty() ? "все" : regions,
                    telegram.tendersThreadId().isEmpty() ? "«Общая»" : telegram.tendersThreadId());
        }
    }

    /**
     * Зовётся в конце прогона из потока импорта (рынок KZ уже стоит, §6). Ничего не бросает: сбой уведомления
     * не должен ронять итог прогона. synchronized — goszakup и СК-Фармация заканчивают в разных потоках.
     */
    public synchronized void afterImport(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        Set<String> ids = new LinkedHashSet<>(pending);
        ids.addAll(sum.getCreatedExtIds());
        if (ids.isEmpty()) return;
        Prepared p;
        try {
            p = readTx.execute(status -> prepare(ids));
        } catch (RuntimeException e) {
            // и новые номера этого прогона: следующий прогон увидит их тендеры уже как «обновлённые», не «созданные»
            pending.addAll(ids);
            log.warn("Telegram: не удалось собрать уведомление о новых тендерах ({}), повтор — со следующим прогоном: {}",
                    ids.size(), ErrorText.of(e));
            fail(sum, "уведомление в Telegram о новых тендерах не собрано (повтор — со следующим прогоном): "
                    + ErrorText.of(e));
            return;
        }
        pending.clear();
        if (p == null || p.text() == null) return;   // никто не прошёл фильтр
        try {
            sender.send(p.text(), !p.sound());
            log.info("Telegram: уведомление о новых тендерах отправлено ({}){}", p.extIds().size(), p.sound() ? " со звуком" : "");
        } catch (RuntimeException e) {
            pending.addAll(p.extIds());
            log.warn("Telegram: уведомление о новых тендерах не ушло ({}), повтор — со следующим прогоном: {}",
                    p.extIds().size(), ErrorText.of(e));
            fail(sum, "уведомление в Telegram о новых тендерах не ушло (повтор — со следующим прогоном): "
                    + ErrorText.of(e));
            return;
        }
        markOurProducts(p.ours());
    }

    /** Наш товар, показанный в «🏥 Новый тендер», — сообщён: уведомитель нашей продукции о нём промолчит. */
    private void markOurProducts(Map<Long, ProductMatchLevel> ours) {
        ours.forEach((tenderId, level) -> {
            try {
                productMatches.markNotified(tenderId, level);
            } catch (RuntimeException e) {
                // ушло, но не отмечено — уведомитель нашей продукции пришлёт ещё раз (лучше повтор, чем потеря)
                log.warn("Telegram: тендер {} с нашим товаром не отмечен сообщённым: {}", tenderId, ErrorText.of(e));
            }
        });
    }

    /** Ошибку видно в тосте и строке «Обновлено…»; причина сбоя самого импорта, если была, не затирается. */
    private static void fail(ImportSummary sum, String text) {
        sum.addSideError(text);
        if (sum.getMessage() != null) sum.setMessage(sum.getMessage() + " · Telegram: уведомление о новых тендерах не ушло");
    }

    /** ours — показанные в тексте тендеры с нашим товаром и их уровень; sound — среди них есть ★. */
    private record Prepared(String text, List<String> extIds, boolean sound, Map<Long, ProductMatchLevel> ours) {}

    private Prepared prepare(Collection<String> extIds) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZONE);
        List<Tender> found = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (String extId : extIds) {
            tenderRepository.findBySourceExtId(extId).filter(t -> qualifies(t, now)).ifPresent(t -> {
                found.add(t);
                used.add(extId);
            });
        }
        if (found.isEmpty()) return new Prepared(null, used, false, Map.of());
        Map<Long, ProductMatchQuery.Summary> ours = products.summaries(found.stream().map(Tender::getId).toList());
        // наш товар — первым: если сообщение придётся обрезать, в «…и ещё N» уйдут чужие тендеры, а не наши
        List<Tender> ordered = found.stream()
                .sorted(Comparator.comparingInt((Tender t) -> rank(ours.get(t.getId()))).reversed())
                .toList();
        List<Card> cards = ordered.stream().map(t -> card(t, ours.get(t.getId()))).toList();
        NewTenderComposer.Composed c = NewTenderComposer.composeShown(cards, publicUrl);
        Map<Long, ProductMatchLevel> shownOurs = new LinkedHashMap<>();
        boolean sound = false;
        for (Tender t : ordered.subList(0, c.shown())) {
            ProductMatchQuery.Summary s = ours.get(t.getId());
            if (s == null || s.level() == null) continue;
            shownOurs.put(t.getId(), s.level());
            sound |= s.level().alert();   // ★ и «✓ подходит» (спека 2026-10-20-product-spec-fit §6.4)
        }
        return new Prepared(c.text(), used, sound, shownOurs);
    }

    /** Порядок в сообщении: ★ (NAMED, COPIED), потом ✓ (FITS), потом 🏷, потом без нашего товара. */
    private static int rank(ProductMatchQuery.Summary s) {
        return s == null || s.level() == null ? -1 : s.level().ordinal();
    }

    private boolean qualifies(Tender t, LocalDateTime now) {
        if (!"ACTIVE".equals(t.getStatus())) return false;
        // срок с временем (B2): приём «до 10:25» в 14:10 уже закрыт, даже если площадка ещё не сменила статус
        if (ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) return false;
        return regions.isEmpty() || regions.contains(norm(t.getRegion()));
    }

    private static Card card(Tender t, ProductMatchQuery.Summary ours) {
        List<LotLine> lots = t.getLots().stream()
                .sorted(Comparator.comparing(TenderLot::getLotNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TenderLot::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(l -> new LotLine(l.getEquipName(), l.getQuantity()))
                .toList();
        return new Card(t.getId(), platformLabel(t.getPlatform()), t.getTenderNumber(), t.getCustomerName(), t.getRegion(),
                t.getDescription(), t.getTotalCost(), t.getDeadline(), t.getDeadlineAt(), lots,
                ours == null || ours.level() == null ? null : ProductMatchQuery.telegramLine(ours));
    }

    private static String platformLabel(TenderPlatform p) {
        if (p == TenderPlatform.SK_PHARMACY) return "СК-Фармация";
        return p == TenderPlatform.GOSZAKUP ? "goszakup" : "тендер";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
