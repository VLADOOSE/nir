package com.vladoose.nir.service.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatMessageResponse;
import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.dto.response.DealTimelineResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ChatService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionLoader;
import com.vladoose.nir.service.execution.ExecutionTexts;
import com.vladoose.nir.service.offer.OfferLabels;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Лента «Переписка и история» (спека 2026-10-18 §3.2, §4.2): всё, у чего уже есть дата, — из существующих таблиц,
 * заметки / звонки / смена этапа — из deal_event. Страница — limit самых поздних раньше курсора, по возрастанию.
 * Строки одной миллисекунды страница не делит: курсор — время с точностью до миллисекунды (фронт шлёт
 * toISOString, в базе — микросекунды), и половина такой группы потерялась бы (фото альбома WhatsApp приходят в одну
 * секунду, события одного запроса — в одну миллисекунду).
 */
@Service
public class DealTimelineService {

    public static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    /** Предел сообщений одной миллисекунды на границе страницы — защита от вырожденных данных. */
    static final int SAME_TIME_MAX = 500;
    /** Запросы цен, отправленные с разрывом меньше минуты, — одно окно «Запросить цены», одна строка. */
    static final Duration RFQ_WINDOW = Duration.ofMinutes(1);
    /** «Без границы» — дальше любой настоящей даты, в пределах timestamptz. */
    public static final OffsetDateTime FAR = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    /** При равном времени событие раньше сообщения (как лента обращения). Сортировка списка устойчивая. */
    private static final Comparator<DealTimelineItem> ORDER = Comparator.comparing(DealTimelineItem::at)
            .thenComparing(i -> "MESSAGE".equals(i.kind()) ? 1 : 0);
    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");

    private final PrivateRequestService privateRequests;
    private final TenderService tenders;
    private final LeadRepository leads;
    private final ChatMessageRepository messages;
    private final ChatService chats;
    private final PriceRequestRepository priceRequests;
    private final InboundEmailRepository inbound;
    private final SupplierReplyFileRepository replyFiles;
    private final ClientOfferRepository offers;
    private final ActivityApplyRepository applies;
    private final DealEventRepository events;
    private final ClientOfferFileRepository offerFiles;
    private final DealExecutionLoader execution;
    private final DealFileRepository execFiles;

    public DealTimelineService(PrivateRequestService privateRequests, TenderService tenders, LeadRepository leads,
                               ChatMessageRepository messages, ChatService chats, PriceRequestRepository priceRequests,
                               InboundEmailRepository inbound, SupplierReplyFileRepository replyFiles,
                               ClientOfferRepository offers, ActivityApplyRepository applies, DealEventRepository events,
                               ClientOfferFileRepository offerFiles, DealExecutionLoader execution,
                               DealFileRepository execFiles) {
        this.privateRequests = privateRequests;
        this.tenders = tenders;
        this.leads = leads;
        this.messages = messages;
        this.chats = chats;
        this.priceRequests = priceRequests;
        this.inbound = inbound;
        this.replyFiles = replyFiles;
        this.offers = offers;
        this.applies = applies;
        this.events = events;
        this.offerFiles = offerFiles;
        this.execution = execution;
        this.execFiles = execFiles;
    }

    @Transactional(readOnly = true)
    public DealTimelineResponse forPrivateRequest(Long id, OffsetDateTime before, int limit) {
        Tender t = privateRequests.findById(id);
        OffsetDateTime cursor = before == null ? FAR : before;
        int n = clamp(limit);
        List<DealTimelineItem> all = new ArrayList<>();
        leads.findFirstByPrivateRequestId(t.getId()).ifPresent(lead -> addLead(all, lead, cursor, n));
        all.add(item(t.getCreatedAt(), "CREATED", "Создана заявка " + t.getTenderNumber(), null, null, null));
        addSupply(all, t, zone(t));
        addOffers(all, t.getId());
        addExecution(all, t, zone(t));
        addEvents(all, t.getId());
        return page(all, cursor, n);
    }

    /** История тендера (§4.2): найден / создан, запросы цен и ответы, заявки на участие и их даты, события. */
    @Transactional(readOnly = true)
    public DealTimelineResponse forTender(Long id, OffsetDateTime before, int limit) {
        Tender t = tenders.findPublic(id);
        OffsetDateTime cursor = before == null ? FAR : before;
        ZoneId zone = zone(t);
        List<DealTimelineItem> all = new ArrayList<>();
        if (t.getCreatedAt() != null) {
            all.add(item(t.getCreatedAt(), "TENDER_FOUND", foundText(t), null, null, null));
        } else if (t.getPublishDate() != null) {
            // импортирован до V26 — когда нашли, неизвестно; известна дата публикации, она и открывает историю
            all.add(new DealTimelineItem(t.getPublishDate().atStartOfDay(zone).toOffsetDateTime(), true,
                    "TENDER_FOUND", publishedText(t), null, null, null, null));
        }
        addSupply(all, t, zone);
        addApplies(all, t.getId(), zone);
        addEvents(all, t.getId());
        return page(all, cursor, clamp(limit));
    }

    public DealTimelineItem toItem(DealEvent e) {
        String link = e.getCode() == null ? null : switch (e.getCode()) {
            case DealEventService.OFFER_STATUS -> "OFFER";
            case DealEventService.APPLY_STATUS -> "APPLY";
            default -> null;
        };
        return new DealTimelineItem(e.getOccurredAt(), false, e.getType().name(), e.getText(), e.getAuthor(), link,
                link == null ? null : e.getRefId(), null);
    }

    /**
     * Обращение, его события и переписка его эпизода — n + 1 последних сообщений раньше курсора и все сообщения
     * самой ранней взятой миллисекунды. Не взятые сообщения раньше её, а строка «Обращение» — не позже любого
     * сообщения эпизода: она остаётся раньше страницы, и признак «есть раньше» выставляется сам.
     */
    private void addLead(List<DealTimelineItem> all, Lead lead, OffsetDateTime cursor, int n) {
        OffsetDateTime start = lead.getReceivedAt() != null ? lead.getReceivedAt() : lead.getCreatedAt();
        all.add(item(start, "LEAD", "Обращение (" + DealTexts.channel(lead.getChannel()) + ")"
                + (blank(lead.getSubject()) ? "" : ": «" + lead.getSubject().strip() + "»"), null, "LEAD", lead.getId()));
        for (LeadEvent e : lead.getEvents()) {
            String kind = switch (e.getType()) {
                case NOTE -> "LEAD_NOTE";
                case CALL -> "LEAD_CALL";
                case STATUS -> "LEAD_STATUS";
                default -> null;   // RECEIVED — строка «Обращение» выше; MESSAGE и SYNC — служебные
            };
            if (kind != null) all.add(item(e.getOccurredAt(), kind, e.getBody(), e.getAuthor(), "LEAD", lead.getId()));
        }
        if (lead.getChat() == null || start == null) return;
        Long chatId = lead.getChat().getId();
        OffsetDateTime to = leads.findFirstByChatIdAndReceivedAtAfterOrderByReceivedAtAsc(chatId, start)
                .map(Lead::getReceivedAt).orElse(FAR);
        List<ChatMessage> newestFirst = new ArrayList<>(
                messages.findForDeal(chatId, start, to, cursor, PageRequest.of(0, n + 1)));
        if (newestFirst.size() > n) {
            // все сообщения самой ранней взятой миллисекунды: страница не делит её, а курсор отрезал бы
            OffsetDateTime oldest = ms(newestFirst.get(newestFirst.size() - 1).getSentAt());
            OffsetDateTime next = oldest.plus(1, ChronoUnit.MILLIS);
            Set<Long> have = newestFirst.stream().map(ChatMessage::getId).collect(Collectors.toSet());
            messages.findForDeal(chatId, oldest.isBefore(start) ? start : oldest, next.isAfter(to) ? to : next, cursor,
                            PageRequest.of(0, SAME_TIME_MAX))
                    .stream().filter(m -> !have.contains(m.getId())).forEach(newestFirst::add);
            newestFirst.sort(Comparator.comparing(ChatMessage::getSentAt).thenComparing(ChatMessage::getId).reversed());
        }
        for (ChatMessageResponse m : chats.toResponses(newestFirst)) {
            all.add(new DealTimelineItem(m.getSentAt(), false, "MESSAGE", null, null, "CHAT", chatId, m));
        }
    }

    private void addSupply(List<DealTimelineItem> all, Tender t, ZoneId zone) {
        List<PriceRequest> prs = priceRequests.findForDealByTenderId(t.getId());
        if (prs.isEmpty()) return;
        Map<Long, PriceRequest> byId = new HashMap<>();
        prs.forEach(pr -> byId.put(pr.getId(), pr));

        List<PriceRequest> sent = new ArrayList<>();
        for (PriceRequest pr : prs) {
            LetterStatus ls = pr.getLetterStatus();
            if (pr.getSentAt() != null && (ls == null || ls == LetterStatus.SENT || ls == LetterStatus.MANUAL)) {
                sent.add(pr);
            }
            if (ls == LetterStatus.NO_EMAIL || ls == LetterStatus.FAILED) {
                all.add(item(pr.getSentAt() != null ? pr.getSentAt() : pr.getCreatedAt(), "LETTER_FAILED",
                        name(pr) + ": письмо не ушло — " + (ls == LetterStatus.NO_EMAIL ? "нет почты" : "ошибка отправки"),
                        null, "PRICE_REQUEST", pr.getId()));
            }
        }
        for (List<PriceRequest> group : rfqWindows(sent)) {
            String mail = group.stream().filter(pr -> pr.getLetterStatus() != LetterStatus.MANUAL)
                    .map(DealTimelineService::name).collect(Collectors.joining(", "));
            String manual = group.stream().filter(pr -> pr.getLetterStatus() == LetterStatus.MANUAL)
                    .map(DealTimelineService::name).collect(Collectors.joining(", "));
            String text = "Запрос цен: " + Stream.of(mail.isEmpty() ? null : "письмом — " + mail,
                            manual.isEmpty() ? null : "передан вручную — " + manual)
                    .filter(Objects::nonNull).collect(Collectors.joining("; "));
            boolean one = group.size() == 1;
            all.add(item(group.get(0).getSentAt(), "RFQ_SENT", text, null, one ? "PRICE_REQUEST" : null,
                    one ? group.get(0).getId() : null));
        }

        Map<Long, OffsetDateTime> lastReply = new HashMap<>();
        for (Object[] row : inbound.findDealRows(byId.keySet())) {
            PriceRequest pr = byId.get(((Number) row[0]).longValue());
            OffsetDateTime at = offset(row[2]);
            if (pr == null || at == null) continue;
            switch ((String) row[1]) {
                case "SUPPLIER_RESPONSE" -> lastReply.merge(pr.getId(), at, (x, y) -> x.isAfter(y) ? x : y);
                case "BOUNCE" -> all.add(item(at, "BOUNCED", name(pr) + ": письмо вернулось", null, "PRICE_REQUEST", pr.getId()));
                case "DELAYED" -> all.add(item(at, "DELAYED", name(pr) + ": письмо задерживается", null, "PRICE_REQUEST", pr.getId()));
                default -> { }
            }
        }
        for (PriceRequest pr : prs) {
            if (!ANSWERED.contains(pr.getStatus())) continue;
            OffsetDateTime at = lastReply.get(pr.getId());
            boolean dateOnly = at == null;
            if (dateOnly) {
                if (pr.getResponseDate() == null) continue;
                at = pr.getResponseDate().atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime();
                // ответ не раньше запроса: цену вписали в день отправки, а запрос ушёл после полудня
                OffsetDateTime asked = pr.getSentAt() != null ? pr.getSentAt() : pr.getCreatedAt();
                if (asked != null && at.isBefore(asked)) at = asked;
            }
            all.add(new DealTimelineItem(at, dateOnly, "DECLINED".equals(pr.getStatus()) ? "DECLINED" : "REPLY",
                    replyText(pr), null, "PRICE_REQUEST", pr.getId(), null));
        }
        for (Object[] row : replyFiles.findDealRows(byId.keySet())) {
            PriceRequest pr = byId.get(((Number) row[0]).longValue());
            OffsetDateTime at = offset(row[2]);
            if (pr == null || at == null) continue;
            all.add(item(at, "REPLY_FILE", "Файл «" + row[1] + "» от " + name(pr), null, "PRICE_REQUEST", pr.getId()));
        }
    }

    /**
     * Окна «Запросить цены»: запросы по времени отправки, новое окно — после разрыва больше минуты. Не по началу
     * минуты: окно на пять поставщиков идёт несколько секунд и может перейти через границу минуты.
     */
    static List<List<PriceRequest>> rfqWindows(List<PriceRequest> sent) {
        List<PriceRequest> byTime = sent.stream()
                .sorted(Comparator.comparing(PriceRequest::getSentAt).thenComparing(PriceRequest::getId)).toList();
        List<List<PriceRequest>> windows = new ArrayList<>();
        OffsetDateTime last = null;
        for (PriceRequest pr : byTime) {
            if (last == null || Duration.between(last, pr.getSentAt()).compareTo(RFQ_WINDOW) > 0) {
                windows.add(new ArrayList<>());
            }
            windows.get(windows.size() - 1).add(pr);
            last = pr.getSentAt();
        }
        return windows;
    }

    private static String foundText(Tender t) {
        if (t.getPlatform() == TenderPlatform.GOSZAKUP) return "Тендер найден на goszakup.gov.kz";
        if (t.getPlatform() == TenderPlatform.SK_PHARMACY) return "Тендер найден на СК-Фармации";
        return "Тендер создан вручную";
    }

    private static String publishedText(Tender t) {
        if (t.getPlatform() == TenderPlatform.GOSZAKUP) return "Тендер опубликован на goszakup.gov.kz";
        if (t.getPlatform() == TenderPlatform.SK_PHARMACY) return "Тендер опубликован на СК-Фармации";
        return "Тендер опубликован";
    }

    private void addApplies(List<DealTimelineItem> all, Long tenderId, ZoneId zone) {
        for (ActivityApply a : applies.findByTenderId(tenderId)) {
            all.add(item(a.getCreatedAt(), "APPLY_CREATED", "Заявка на участие № " + a.getId() + " создана", null,
                    "APPLY", a.getId()));
            if (a.getContractSignedAt() != null) {
                all.add(dateItem(a.getContractSignedAt(), zone, "CONTRACT", "Договор"
                        + (blank(a.getContractNumber()) ? "" : " № " + a.getContractNumber().strip()) + " подписан", a.getId()));
            }
            if (a.getDeliveredAt() != null) all.add(dateItem(a.getDeliveredAt(), zone, "DELIVERED", "Поставлено", a.getId()));
            if (a.getPaidAt() != null) all.add(dateItem(a.getPaidAt(), zone, "PAID", "Оплачено", a.getId()));
        }
    }

    private static DealTimelineItem dateItem(LocalDate d, ZoneId zone, String kind, String text, Long applyId) {
        return new DealTimelineItem(d.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime(), true, kind, text, null,
                "APPLY", applyId, null);
    }

    private void addOffers(List<DealTimelineItem> all, Long tenderId) {
        List<ClientOffer> list = offers.findByTenderIdOrderByIdDesc(tenderId);
        Map<Long, String> fileNames = new HashMap<>();
        List<Long> fileIds = list.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.FILE).map(ClientOffer::getId).toList();
        if (!fileIds.isEmpty()) for (ClientOfferFileMeta m : offerFiles.findMetaByOfferIds(fileIds)) fileNames.put(m.offerId(), m.name());
        for (ClientOffer o : list) {
            String created = o.getOrigin() == ClientOfferOrigin.FILE
                    ? OfferLabels.of(o) + " занесено из файла" + (fileNames.containsKey(o.getId()) ? " «" + fileNames.get(o.getId()) + "»" : "")
                    : OfferLabels.of(o) + " создано";
            all.add(item(o.getCreatedAt(), "OFFER_CREATED", created, o.getCreatedBy(), "OFFER", o.getId()));
            if (o.getSentAt() != null) {
                all.add(item(o.getSentAt(), "OFFER_SENT", OfferLabels.of(o) + " отправлено", null, "OFFER", o.getId()));
            }
        }
    }

    private void addEvents(List<DealTimelineItem> all, Long tenderId) {
        for (DealEvent e : events.findByTenderIdOrderByOccurredAtAscIdAsc(tenderId)) all.add(toItem(e));
    }

    /**
     * Самые поздние n строк раньше курсора, по возрастанию. Группу строк одной миллисекунды страница не делит — берёт
     * целиком: курсор следующей страницы — время её первой строки (фронт — до миллисекунды), раньше всей группы.
     */
    static DealTimelineResponse page(List<DealTimelineItem> all, OffsetDateTime cursor, int n) {
        List<DealTimelineItem> eligible = all.stream().filter(i -> i.at() != null && i.at().isBefore(cursor))
                .sorted(ORDER).toList();
        int from = Math.max(0, eligible.size() - n);
        while (from > 0 && ms(eligible.get(from - 1).at()).isEqual(ms(eligible.get(from).at()))) from--;
        return new DealTimelineResponse(new ArrayList<>(eligible.subList(from, eligible.size())), from > 0);
    }

    static OffsetDateTime ms(OffsetDateTime t) {
        return t.truncatedTo(ChronoUnit.MILLIS);
    }

    static int clamp(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * Исполнение сделки (спека 2026-10-19-deal-execution §10): даты — «только дата», файлы — время, когда приложили. Строки
     * одного дня стоят на одном полудне, сортировка устойчива — поэтому добавляются в порядке процесса: договор → оплата
     * клиента → поставщики (заказ, оплата, отгрузка) → поставка → документы.
     */
    private void addExecution(List<DealTimelineItem> all, Tender t, ZoneId zone) {
        DealExecutionData d = execution.load(t);
        DealExecution e = d.row();
        if (e != null && e.getContractDate() != null && e.getContractKind() != ContractKind.NONE) {
            all.add(execDate(e.getContractDate(), zone, "EXEC_CONTRACT", contractText(e), null, "EXEC_CONTRACT"));
        }
        for (DealPayment p : d.clientPayments()) {
            all.add(execDate(p.getPaidOn(), zone, "EXEC_PAY_CLIENT",
                    withNote("Оплата клиента: " + ExecutionTexts.money(p.getAmount(), d.currency()), p.getNote()),
                    p.getCreatedBy(), "EXEC_PAYMENT"));
        }
        Map<Long, String> supplierNames = new HashMap<>();
        for (DealExecutionData.SupplierLine s : d.supplierLines()) {
            if (s.rowId() != null) supplierNames.put(s.rowId(), s.name());
            if (s.orderedOn() != null) {
                all.add(execDate(s.orderedOn(), zone, "EXEC_ORDER", withNote("Заказ у " + s.name(), s.orderNote()), null, "EXEC_SUPPLIERS"));
            }
            for (DealPayment p : s.payments()) {
                all.add(execDate(p.getPaidOn(), zone, "EXEC_PAY_SUPPLIER",
                        withNote("Оплата " + s.name() + ": " + ExecutionTexts.money(p.getAmount(), s.currency()), p.getNote()),
                        p.getCreatedBy(), "EXEC_SUPPLIERS"));
            }
            if (s.shippedOn() != null) {
                all.add(execDate(s.shippedOn(), zone, "EXEC_SHIPPED", withNote(s.name() + " отгрузил", s.shipNote()), null, "EXEC_SUPPLIERS"));
            }
        }
        if (e != null && e.getDeliveredOn() != null) {
            all.add(execDate(e.getDeliveredOn(), zone, "EXEC_DELIVERED", withNote("Поставлено клиенту", e.getDeliveryNote()),
                    null, "EXEC_DELIVERY"));
        }
        if (e != null && e.getDocsOn() != null) {
            all.add(execDate(e.getDocsOn(), zone, "EXEC_DOCS", withNote("Закрывающие документы подписаны", e.getDocsNote()),
                    null, "EXEC_DOCS"));
        }
        for (DealFileMeta f : execFiles.findMetaByTenderId(t.getId())) {
            all.add(new DealTimelineItem(f.uploadedAt(), false, "EXEC_FILE", "«" + f.name() + "» — " + filePlace(f, supplierNames),
                    f.uploadedBy(), fileBlock(f.stage()), null, null));
        }
    }

    private static DealTimelineItem execDate(LocalDate d, ZoneId zone, String kind, String text, String author, String block) {
        return new DealTimelineItem(d.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime(), true, kind, text, author, block,
                null, null);
    }

    static String contractText(DealExecution e) {
        boolean invoice = e.getContractKind() == ContractKind.INVOICE;
        String no = blank(e.getContractNumber()) ? "" : " № " + e.getContractNumber().strip();
        return (invoice ? "Счёт" : "Договор") + no + " от " + DealTexts.ddMM(e.getContractDate())
                + (invoice ? " выставлен" : " подписан");
    }

    private static String withNote(String text, String note) {
        return blank(note) ? text : text + " — " + note.strip();
    }

    private static String filePlace(DealFileMeta f, Map<Long, String> names) {
        String name = f.dealSupplierId() == null ? "" : names.getOrDefault(f.dealSupplierId(), "поставщику");
        return switch (f.stage()) {
            case CONTRACT -> "к договору";
            case CLIENT_PAYMENT -> "к оплате клиента";
            case SUPPLIER -> f.paymentId() != null ? "к оплате " + name : "к поставщику " + name;
            case DELIVERY -> "к поставке";
            case DOCS -> "к документам";
        };
    }

    private static String fileBlock(DealFileStage stage) {
        return switch (stage) {
            case CONTRACT -> "EXEC_CONTRACT";
            case CLIENT_PAYMENT -> "EXEC_PAYMENT";
            case SUPPLIER -> "EXEC_SUPPLIERS";
            case DELIVERY -> "EXEC_DELIVERY";
            case DOCS -> "EXEC_DOCS";
        };
    }

    static ZoneId zone(Tender t) {
        return (t.getMarket() == null ? MarketContext.get() : t.getMarket()).zone();
    }

    static DealTimelineItem item(OffsetDateTime at, String kind, String text, String author, String linkType, Long linkId) {
        return new DealTimelineItem(at, false, kind, text, author, linkType, linkId, null);
    }

    private static String replyText(PriceRequest pr) {
        if ("DECLINED".equals(pr.getStatus())) return name(pr) + " отказался";
        long asked = pr.getItems().size();
        long priced = pr.getItems().stream().filter(i -> i.getResponsePrice() != null).count();
        return priced > 0 ? name(pr) + " ответил: цены по " + priced + " из " + asked : name(pr) + " ответил — цен нет";
    }

    static String name(PriceRequest pr) {
        return pr.getDistributor() == null ? "Поставщик" : pr.getDistributor().getName();
    }

    /** Время из нативного запроса: Hibernate 6 отдаёт timestamptz как OffsetDateTime или Instant, драйвер — Timestamp. */
    static OffsetDateTime offset(Object v) {
        if (v instanceof OffsetDateTime o) return o;
        if (v instanceof Instant i) return i.atOffset(ZoneOffset.UTC);
        if (v instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        if (v instanceof LocalDateTime l) return l.atOffset(ZoneOffset.UTC);
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
