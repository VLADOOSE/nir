package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.PreparedFile;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import com.vladoose.nir.util.SupplierReplyDeclineDetector;
import com.vladoose.nir.util.SupplierReplyPriceParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Запись письма (спека §3.3): классификация, правки запроса КП, строка «Входящих» с готовым текстом уведомления и
 * сдвиг курсора — ОДНОЙ транзакцией на письмо. Сеть сюда не заходит: письмо уже разобрано (ParsedMail).
 * Рынок ставит вызывающий (MarketContext) — это рынок ящика: им стампится строка «Входящих», по нему ищутся дубли.
 * Запрос КП ищется по id в любом рынке (см. {@link #write}), и уведомление о нём собирается по рынку запроса.
 */
@Service
public class MailIngestWriter {

    /** Итог записи: вид письма, что сделано с запросом КП, id строки «Входящих»; у дубля всё null, кроме duplicate. */
    public record WriteResult(MailClass mailClass, KpOutcome outcome, Long inboundId, boolean duplicate) {}

    private final InboundEmailRepository inboundRepo;
    private final PriceRequestRepository priceRequestRepo;
    private final MailCursorRepository cursorRepo;
    private final TelegramSettings telegram;
    private final String mailbox;
    private final ClassifierRules rules;
    private final String publicUrl;
    private final SupplierReplyService replies;

    public MailIngestWriter(InboundEmailRepository inboundRepo, PriceRequestRepository priceRequestRepo,
                            MailCursorRepository cursorRepo, TelegramSettings telegram,
                            @Value("${mail.imap.username:}") String mailbox,
                            @Value("${spring.mail.username:}") String sendFrom,
                            @Value("${leads.westmed.notification-from:info@westmed.kz}") String siteNotificationFrom,
                            @Value("${mail.imap.client-requests:true}") boolean clientRequests,
                            @Value("${ais.public-url:}") String publicUrl,
                            SupplierReplyService replies) {
        this.inboundRepo = inboundRepo;
        this.priceRequestRepo = priceRequestRepo;
        this.cursorRepo = cursorRepo;
        this.telegram = telegram;
        this.mailbox = lower(mailbox);
        this.rules = new ClassifierRules(lower(sendFrom), lower(siteNotificationFrom), clientRequests);
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim();
        this.replies = replies;
    }

    /** Адрес ящика нижним регистром — ключ курсора. */
    public String mailbox() { return mailbox; }

    @Transactional
    public WriteResult write(ParsedMail m, long uidValidity) {
        return write(m, List.of(), uidValidity);
    }

    /**
     * files — файлы ответа поставщика, разобранные вне транзакции (SupplierReplyFiles.prepareFor). Цены из файла
     * подставлены уверенно — текст письма на цену не разбирается (решение плана 4); иначе — прежний разбор текста.
     */
    @Transactional
    public WriteResult write(ParsedMail m, List<PreparedFile> files, long uidValidity) {
        String messageId = messageIdOf(m.messageId());
        if (messageId != null && inboundRepo.existsByMailboxAndMessageId(mailbox, messageId)) {
            moveCursor(uidValidity, m.uid());                 // уже записано (сброс курсора, повторная доставка)
            return new WriteResult(null, null, null, true);
        }
        Classification c = MailClassifier.classify(m, rules);
        if (c.mailClass() == MailClass.SITE_NOTIFICATION) {
            moveCursor(uidValidity, m.uid());
            return new WriteResult(c.mailClass(), null, null, false);
        }
        // Без гарда рынка — намеренно: КП обоих рынков уходят с одного ящика (spring.mail.username, Reply-To zakup@),
        // и ответы поставщиков Регион-Мед приходят сюда же. findById (em.find) фильтр рынка не применяет — запрос
        // находится по id в любом рынке, как в прежнем приёме; NOT_FOUND — только когда запроса с таким id нет вовсе.
        PriceRequest pr = c.kpId() == null ? null : priceRequestRepo.findById(c.kpId()).orElse(null);
        KpOutcome outcome = null;
        SupplierReplyService.ReplyOutcome fileOutcome = null;
        if (c.mailClass() == MailClass.SUPPLIER_RESPONSE) {
            if (pr == null) {
                outcome = KpOutcome.NOT_FOUND;
            } else {
                if (!files.isEmpty()) fileOutcome = replies.ingest(pr, files, null);
                if (fileOutcome != null && fileOutcome.applied() > 0) {
                    // цены из файла — текст письма не разбирается (решение плана 4)
                    outcome = KpOutcome.FILE_PRICES;
                    SupplierReplyService.markResponded(pr, cut(m.body(), 4000));
                    priceRequestRepo.save(pr);
                } else {
                    outcome = applySupplierResponse(pr, m.rawBody(), m.body());
                }
            }
        }
        // Все виды явно, без default: новый вид письма не уйдёт молча в «Прочее» — его не пропустит компилятор
        InboundType type = switch (c.mailClass()) {
            case SUPPLIER_RESPONSE -> InboundType.SUPPLIER_RESPONSE;
            case BOUNCE -> InboundType.BOUNCE;
            case DELAYED -> InboundType.DELAYED;
            case AUTO_REPLY -> InboundType.AUTO_REPLY;
            case CLIENT_REQUEST -> InboundType.CLIENT_REQUEST;
            // своё письмо — «Прочее» без уведомления; уведомление сайта сюда не доходит (возврат выше)
            case OWN, UNMATCHED, SITE_NOTIFICATION -> InboundType.UNMATCHED;
        };
        boolean clientRequest = type == InboundType.CLIENT_REQUEST;
        InboundEmail e = InboundEmail.builder()
                .mailbox(mailbox).imapUid(m.uid()).messageId(messageId)
                .fromAddress(cut(m.from(), 320)).subject(cut(m.subject(), 998)).receivedAt(m.receivedAt())
                .type(type).matchedPriceRequestId(pr == null ? null : pr.getId())
                .attachmentName(clientRequest ? cut(m.excelName(), 255) : null)
                .attachment(clientRequest ? m.excelBytes() : null)
                .excerpt(cut(m.body(), 2000))
                .status(InboundStatus.NEW)
                .build();
        if (c.mailClass() != MailClass.OWN && telegram.isConfigured()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            queue(e, MailNotificationComposer.compose(m, c, pr == null ? null : snapshot(pr), outcome,
                    fileLine(fileOutcome), context(now, pr)), now);
        }
        inboundRepo.save(e);                                   // @PrePersist стампит market ящика из MarketContext
        if (fileOutcome != null && !fileOutcome.fileIds().isEmpty()) replies.linkInbound(fileOutcome.fileIds(), e.getId());
        moveCursor(uidValidity, m.uid());
        return new WriteResult(c.mailClass(), outcome, e.getId(), false);
    }

    /**
     * Строка уведомления о файлах ответа (спека §3.5); файлов нет — null. Имена файлов присылает поставщик —
     * MailNotificationComposer сводит строку в одну (переводы строк не станут системной строкой, R15).
     */
    static String fileLine(SupplierReplyService.ReplyOutcome o) {
        if (o == null || o.fileIds().isEmpty()) return null;
        String name = o.parsedName() != null ? o.parsedName() : o.fileName();
        if (o.closed()) return "Файл «" + name + "» сохранён — запрос закрыт, цены не меняли";
        if (o.applied() > 0) {
            return "💡 Цены из файла «" + name + "»: подставлено " + o.applied() + " из " + o.positions()
                    + (o.rate() != null ? " по курсу " + SupplierReplyService.rateText(o.rate()) : "") + " — сверьте"
                    + (o.manualKept() > 0 ? "; ручные цены не тронуты: " + o.manualKept() : "");
        }
        if (MoneyText.MIXED.equals(o.foreignCurrency())) {
            return "Цены в файле «" + name + "» не подставлены: в документе названы разные валюты — сверьте по документу и впишите цены вручную";
        }
        if (o.foreignCurrency() != null) {
            return "Цены в файле «" + name + "» — в " + MoneyText.label(o.foreignCurrency()) + ", не подставлены — впишите курс на экране сверки";
        }
        if (o.parsedName() != null) {
            return o.manualKept() > 0
                    ? "Цены из файла «" + name + "» не подставлены: ручные цены не тронуты — " + o.manualKept()
                    : "Цены из файла «" + name + "» не сопоставились уверенно — сверьте вручную";
        }
        if (o.unreadable() != null) return "Файл «" + o.unreadable() + "» не разобран — введите цены вручную";
        if (o.viewOnly()) return o.image() ? "📄 КП картинкой — внесите цены по документу" : "📄 КП в PDF — внесите цены по документу";
        return "📎 Файл «" + name + "» — в ответе поставщика в АИС";
    }

    /** Письмо, которое не разобралось или не записалось: короткая строка, курсор дальше (спека §3.4). */
    @Transactional
    public void writeBroken(BrokenMail b, long uidValidity) {
        InboundEmail e = InboundEmail.builder()
                .mailbox(mailbox).imapUid(b.uid()).messageId(messageIdOf(b.messageId()))
                .fromAddress(cut(b.from(), 320)).subject(cut(b.subject(), 998)).receivedAt(b.receivedAt())
                .type(InboundType.UNMATCHED)
                .excerpt("Письмо не удалось разобрать (" + b.errorClass() + ") — откройте его в почте")
                .status(InboundStatus.NEW)
                .build();
        if (telegram.isConfigured()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            queue(e, MailNotificationComposer.composeBroken(b, context(now, null)), now);
        }
        inboundRepo.save(e);
        moveCursor(uidValidity, b.uid());
    }

    /** Первый запуск: курсор — на последнее письмо ящика, даже если в окне ничего не было (0 — ящик пуст). */
    @Transactional
    public void moveCursorTo(long uidValidity, long uid) {
        moveCursor(uidValidity, uid);
    }

    /** В пределах одного UIDVALIDITY курсор назад не ходит; новый UIDVALIDITY — отсчёт заново. */
    private void moveCursor(long uidValidity, long uid) {
        cursorRepo.advance(MailCursorKey.inbox(mailbox), uidValidity, uid);
    }

    /**
     * Ответ поставщика (логика прежнего MailReceiveService.matchSupplierResponse без изменений): только из CREATED/SENT;
     * одно-лотовый — авторазбор цены (ручную не затираем); иначе явный отказ → DECLINED; иначе RESPONDED.
     * Цену и отказ ищут в сыром теле (parseInput: text/plain, а без него — HTML как есть), как прежде: у разбора своё
     * снятие тегов, а в готовом тексте {@code <} и {@code >} — обычные символы ({@code срок <30 дней},
     * {@code Анна <anna@x.kz>}), и разбор выбросил бы всё между ними вместе с ценой. Заметка запроса — текст для людей
     * (displayText).
     */
    private KpOutcome applySupplierResponse(PriceRequest pr, String parseInput, String displayText) {
        String st = pr.getStatus();
        if (!"CREATED".equals(st) && !"SENT".equals(st)) return KpOutcome.UNCHANGED;
        pr.setResponseDate(LocalDate.now());
        pr.setNote(cut(displayText, 4000));
        KpOutcome outcome = null;
        if (pr.getItems().size() == 1) {
            PriceRequestItem item = pr.getItems().get(0);
            if (item.getResponsePrice() != null) {
                outcome = KpOutcome.PRICE_SET;                  // цена уже введена вручную
            } else {
                Optional<SupplierReplyPriceParser.ParsedPrice> pp = SupplierReplyPriceParser.parse(parseInput, pr.getMarket());
                if (pp.isPresent()) {
                    item.setResponsePrice(pp.get().price());
                    item.setResponseAuto(true);                 // 💡 — признак строки ответа (V30), не текст примечания
                    item.setResponseNote("Цена распознана из письма."
                            + (pp.get().term() != null ? " Срок: " + pp.get().term() + "." : "")
                            + (pp.get().matchedSnippet() != null ? " Контекст: «" + pp.get().matchedSnippet() + "»." : ""));
                    outcome = KpOutcome.PRICE_PARSED;
                }
            }
        }
        if (outcome != null) {
            pr.setStatus("RESPONDED");
        } else if (SupplierReplyDeclineDetector.isDecline(parseInput)) {
            pr.setStatus("DECLINED");
            outcome = KpOutcome.DECLINED;
        } else {
            pr.setStatus("RESPONDED");
            outcome = pr.getItems().size() > 1 ? KpOutcome.MULTI_LOT : KpOutcome.NO_PRICE;
        }
        priceRequestRepo.save(pr);                              // cascade ALL сохранит правку item
        return outcome;
    }

    static KpSnapshot snapshot(PriceRequest pr) {
        Tender t = pr.getTender();
        Distributor d = pr.getDistributor();
        List<KpSnapshot.LotLine> lots = pr.getItems().stream()
                .map(i -> new KpSnapshot.LotLine(i.getTenderLot() == null ? null : i.getTenderLot().getEquipName(),
                        i.getRequestedQuantity()))
                .toList();
        BigDecimal price = pr.getItems().size() == 1 ? pr.getItems().get(0).getResponsePrice() : null;
        return new KpSnapshot(pr.getId(), d == null ? "поставщик" : d.getName(), d == null ? null : d.getEmail(),
                t.getId(), t.getTenderNumber(), t.getSource() == Source.PRIVATE_REQUEST, lots, pr.getStatus(), price,
                pr.isEmailOverride());
    }

    /**
     * Контекст текста уведомления. Рынок — запроса КП, если он найден: ответ поставщика Регион-Мед приходит в ящик
     * West-Med, а валюта, часовой пояс «Получено» и ?market= в ссылке должны быть рынка запроса — иначе ссылка не
     * откроет его тендер. Запроса нет — рынок ящика.
     */
    private ComposeContext context(OffsetDateTime now, PriceRequest pr) {
        Market market = pr != null && pr.getMarket() != null ? pr.getMarket() : MarketContext.get();
        return new ComposeContext(mailbox, market, publicUrl, now);
    }

    private static void queue(InboundEmail e, MailNotification n, OffsetDateTime now) {
        e.setNotifyStatus(NotifyStatus.PENDING);
        e.setNotifyText(n.text());
        e.setNotifySilent(n.silent());
        e.setNotifyQueuedAt(now);
    }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    /**
     * Message-ID для дедупа: пустой заголовок или {@code <>} — как отсутствующий (null), иначе все такие письма после
     * первого считались бы повтором и пропадали без строки и уведомления.
     */
    static String messageIdOf(String raw) {
        String id = cut(raw, 998);
        return id == null || id.replace("<", "").replace(">", "").isBlank() ? null : id;
    }

    /** Срез под длину колонки; эмодзи пополам не режет ({@link MailText#safeCut}). */
    private static String cut(String s, int max) {
        return MailText.safeCut(s, max);
    }
}
