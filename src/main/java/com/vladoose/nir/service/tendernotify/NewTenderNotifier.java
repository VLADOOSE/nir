package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.TenderRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 */
@Component
public class NewTenderNotifier {

    private static final Logger log = LoggerFactory.getLogger(NewTenderNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Oral");
    static final String DEFAULT_REGION = "Западно-Казахстанская область";

    /** Отправка готового текста; в работе — тема тендеров Bot API. */
    interface Sender {
        void send(String text);
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

    /** Не ушедшие из-за сбоя Telegram — уйдут со следующим прогоном (если ещё действуют). */
    private final Set<String> pending = new LinkedHashSet<>();

    @Autowired
    public NewTenderNotifier(TenderRepository tenderRepository, PlatformTransactionManager txManager,
                             TelegramSettings telegram, TelegramClient client,
                             @Value("${tenders.notify.enabled:false}") boolean enabled,
                             @Value("${tenders.notify.regions:}") String regions,
                             @Value("${ais.public-url:}") String publicUrl) {
        this(tenderRepository, txManager, telegram, client::sendTenders, enabled, regions, publicUrl, Clock.systemUTC());
    }

    NewTenderNotifier(TenderRepository tenderRepository, PlatformTransactionManager txManager, TelegramSettings telegram,
                      Sender sender, boolean enabled, String regions, String publicUrl, Clock clock) {
        this.tenderRepository = tenderRepository;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.regions = parseRegions(regions);
        this.publicUrl = publicUrl;
        this.clock = clock;
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
            sender.send(p.text());
            log.info("Telegram: уведомление о новых тендерах отправлено ({})", p.extIds().size());
        } catch (RuntimeException e) {
            pending.addAll(p.extIds());
            log.warn("Telegram: уведомление о новых тендерах не ушло ({}), повтор — со следующим прогоном: {}",
                    p.extIds().size(), ErrorText.of(e));
            fail(sum, "уведомление в Telegram о новых тендерах не ушло (повтор — со следующим прогоном): "
                    + ErrorText.of(e));
        }
    }

    /** Ошибку видно в тосте и строке «Обновлено…»; причина сбоя самого импорта, если была, не затирается. */
    private static void fail(ImportSummary sum, String text) {
        sum.addSideError(text);
        if (sum.getMessage() != null) sum.setMessage(sum.getMessage() + " · Telegram: уведомление о новых тендерах не ушло");
    }

    private record Prepared(String text, List<String> extIds) {}

    private Prepared prepare(Collection<String> extIds) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        List<Card> cards = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (String extId : extIds) {
            tenderRepository.findBySourceExtId(extId).filter(t -> qualifies(t, today)).ifPresent(t -> {
                cards.add(card(t));
                used.add(extId);
            });
        }
        return new Prepared(NewTenderComposer.compose(cards, publicUrl), used);
    }

    private boolean qualifies(Tender t, LocalDate today) {
        if (!"ACTIVE".equals(t.getStatus())) return false;
        if (t.getDeadline() != null && t.getDeadline().isBefore(today)) return false;
        return regions.isEmpty() || regions.contains(norm(t.getRegion()));
    }

    private static Card card(Tender t) {
        List<LotLine> lots = t.getLots().stream()
                .sorted(Comparator.comparing(TenderLot::getLotNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TenderLot::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(l -> new LotLine(l.getEquipName(), l.getQuantity()))
                .toList();
        return new Card(t.getId(), platformLabel(t.getPlatform()), t.getTenderNumber(), t.getCustomerName(), t.getRegion(),
                t.getDescription(), t.getTotalCost(), t.getDeadline(), lots);
    }

    private static String platformLabel(TenderPlatform p) {
        if (p == TenderPlatform.SK_PHARMACY) return "СК-Фармация";
        return p == TenderPlatform.GOSZAKUP ? "goszakup" : "тендер";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
