package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.dto.response.ChatFileRow;
import com.vladoose.nir.dto.response.OfferFileCandidate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import com.vladoose.nir.service.readyoffer.OfferFileLine;
import com.vladoose.nir.service.readyoffer.OfferFileLines;
import com.vladoose.nir.service.readyoffer.ReadyOfferHints;
import com.vladoose.nir.service.readyoffer.ReadyOfferReader;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.ReplyMatcher;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Готовое КП (спека 2026-10-19): КП, сделанное вне конструктора, — обычное КП клиенту с файлом. Строки — из таблиц файла
 * (ReadyOfferReader); позиции заявки — из строк КП (заявка пустая) или сопоставлением ReplyMatcher (те же пороги, что у
 * ответов поставщиков: лучше не связать, чем перепутать); закупка — лучший ответ поставщика по позиции, как
 * «Создать КП клиенту». Номер — из документа, счётчик «исх. №» не тратится. Всё проверяется до первой записи.
 */
@Service
public class ReadyOfferService {

    /** Предел строк PUT КП (ClientOfferUpdateRequest.items) — больше автосохранение редактора не примет. */
    public static final int MAX_LINES = 500;

    public record Upload(String fileName, String mimeType, byte[] bytes) {}

    private record Source(String fileName, String mimeType, byte[] bytes, ChatAttachment attachment) {}

    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ClientOfferRequestService requestOffers;
    private final ClientOfferRepository repository;
    private final ClientOfferFileRepository files;
    private final CompanyProfileService profiles;
    private final MedRegistryRepository registry;
    private final ReadyOfferReader reader;
    private final ReadyOfferHints hints;
    private final DealEventService dealEvents;
    private final ChatAttachmentRepository attachments;

    public ReadyOfferService(PrivateRequestService privateRequests, ClientOfferService offers, ClientOfferRequestService requestOffers,
                             ClientOfferRepository repository, ClientOfferFileRepository files, CompanyProfileService profiles,
                             MedRegistryRepository registry, ReadyOfferReader reader, ReadyOfferHints hints,
                             DealEventService dealEvents, ChatAttachmentRepository attachments) {
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.requestOffers = requestOffers;
        this.repository = repository;
        this.files = files;
        this.profiles = profiles;
        this.registry = registry;
        this.reader = reader;
        this.hints = hints;
        this.dealEvents = dealEvents;
        this.attachments = attachments;
    }

    @Transactional
    public ClientOffer attach(Long tenderId, Upload upload, Long attachmentId, ClientOfferStatus status, LocalDate sentOn,
                              String author) {
        Tender t = privateRequests.findById(tenderId);
        Market market = t.getMarket() == null ? MarketContext.get() : t.getMarket();
        ZoneId zone = market.zone();
        if (status != ClientOfferStatus.SENT && status != ClientOfferStatus.ACCEPTED) {
            throw new BadRequestException("Статус готового КП — «Отправлено» или «Клиент согласился»");
        }
        if (sentOn == null) throw new BadRequestException("Укажите дату отправки");
        if (sentOn.isAfter(LocalDate.now(zone))) throw new BadRequestException("Дата отправки ещё не наступила");
        Source src = source(t, upload, attachmentId);
        if (SupplierReplyFiles.kind(src.fileName(), src.mimeType(), src.bytes()) == ReplyFileKind.OTHER) {
            throw new BadRequestException("Нужен Word, Excel, PDF или картинка");
        }
        ReadyOfferReader.Result read = reader.read(src.fileName(), src.mimeType(), src.bytes(), market.currencyCode());
        List<ClientOffer> existing = repository.findByTenderIdOrderByIdDesc(t.getId());
        Optional<ClientOffer> ais = ReadyOfferHints.aisOffer(read.header(), existing);
        if (ais.isPresent()) throw new BadRequestException(aisText(ais.get()));
        if (read.lines().size() > MAX_LINES) {
            throw new BadRequestException("В файле больше " + MAX_LINES + " строк — такое КП АИС не держит");
        }

        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = offers.fileBase(market, author);
        o.setNumber(read.header().number());
        o.setOfferDate(read.header().date() != null ? read.header().date() : sentOn);
        o.setStatus(status);
        o.setSentAt(sentAt(src, sentOn, zone));
        o.setFacility(t.getFacility());
        o.setRecipient(ClientOfferRequestService.recipient(t));
        o.setTenderId(t.getId());
        addItems(o, t, read, p);
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        ClientOffer saved = repository.saveAndFlush(o);

        files.save(ClientOfferFile.builder().offerId(saved.getId()).fileName(src.fileName())
                .mimeType(src.mimeType() == null ? null : cut(src.mimeType(), 100)).sizeBytes(src.bytes().length)
                .sha256(SupplierReplyFiles.sha256(src.bytes())).content(src.bytes()).kind(read.kind())
                .parseStatus(read.status()).parseNote(note(read, market)).tables(read.tables()).pages(read.pages())
                .fileTotal(read.fileTotal()).chatAttachmentId(src.attachment() == null ? null : src.attachment().getId())
                .uploadedBy(author).build());
        if (status == ClientOfferStatus.ACCEPTED) {
            dealEvents.stage(t.getId(), DealEventService.OFFER_STATUS, OfferLabels.of(saved) + ": клиент согласился", saved.getId());
        }
        return saved;
    }

    /** Файл готового КП: гард рынка — через КП (404 чужому), у КП без файла — 404. */
    @Transactional(readOnly = true)
    public ClientOfferFile file(Long offerId) {
        ClientOffer o = offers.get(offerId);
        return files.findByOfferId(o.getId()).orElseThrow(() -> new NotFoundException("У КП нет файла"));
    }

    @Transactional(readOnly = true)
    public List<OfferFileCandidate> candidates(Long tenderId) {
        Tender t = privateRequests.findById(tenderId);
        ZoneId zone = (t.getMarket() == null ? MarketContext.get() : t.getMarket()).zone();
        List<ClientOffer> list = repository.findByTenderIdOrderByIdDesc(t.getId());
        Map<Long, ClientOffer> used = hints.usedBy(list);
        List<OfferFileCandidate> out = new ArrayList<>();
        for (ChatFileRow f : hints.ourFiles(t)) {
            ClientOffer u = used.get(f.attachmentId());
            ClientOffer ais = ReadyOfferHints.aisOffer(ReadyOfferHints.nameHeader(f.fileName()), list).orElse(null);
            out.add(new OfferFileCandidate(f.attachmentId(), f.fileName(), f.sizeBytes(),
                    SupplierReplyFiles.kind(f.fileName() == null ? "" : f.fileName(), f.mimeType(), new byte[0]).name(),
                    f.sentAt(), f.sentAt().atZoneSameInstant(zone).toLocalDate(), f.channel() == null ? null : f.channel().name(),
                    f.subject(), snippet(f.body()), ReadyOfferHints.looksLikeOffer(f),
                    u == null ? null : u.getId(), u == null ? null : OfferLabels.of(u),
                    ais == null ? null : ais.getId(), ais == null ? null : OfferLabels.of(ais),
                    ais != null && ais.getStatus() == ClientOfferStatus.DRAFT));
        }
        return out;
    }

    /** «Не КП» / «Нет»: подсказка про этот файл больше не показывается. */
    @Transactional
    public void dismiss(Long tenderId, Long attachmentId) {
        Tender t = privateRequests.findById(tenderId);
        hints.requireOurFile(t, attachmentId);
        attachments.markOfferHintDismissed(attachmentId);
    }

    /** «Отметить отправленным»: черновик конструктора, чей PDF / Word ушёл этим сообщением, — «Отправлено» его временем. */
    @Transactional
    public ClientOffer markSent(Long tenderId, Long attachmentId) {
        Tender t = privateRequests.findById(tenderId);
        ChatFileRow f = hints.requireOurFile(t, attachmentId);
        ClientOffer o = ReadyOfferHints.aisOffer(ReadyOfferHints.nameHeader(f.fileName()), repository.findByTenderIdOrderByIdDesc(t.getId()))
                .orElseThrow(() -> new BadRequestException("Это не файл КП из конструктора этой сделки"));
        if (o.getStatus() != ClientOfferStatus.DRAFT) throw new BadRequestException(OfferLabels.of(o) + " уже не черновик");
        return offers.setStatus(o.getId(), ClientOfferStatus.SENT, f.sentAt());
    }

    private static String snippet(String body) {
        if (body == null) return null;
        String s = body.replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private Source source(Tender t, Upload upload, Long attachmentId) {
        if ((upload == null) == (attachmentId == null)) throw new BadRequestException("Выберите файл: с компьютера или из переписки");
        if (upload != null) {
            if (upload.bytes() == null || upload.bytes().length == 0) throw new BadRequestException("Файл пустой");
            return new Source(SupplierReplyFiles.fileName(upload.fileName()), upload.mimeType(), upload.bytes(), null);
        }
        ChatAttachment a = hints.ourAttachment(t, attachmentId);
        return new Source(SupplierReplyFiles.fileName(a.getFileName()), a.getMimeType(), a.getContent(), a);
    }

    /** Время отправки: у файла переписки — время сообщения, если дата та же; иначе полдень даты по часам рынка. */
    static OffsetDateTime sentAt(Source src, LocalDate sentOn, ZoneId zone) {
        if (src.attachment() != null) {
            OffsetDateTime at = src.attachment().getMessage().getSentAt();
            if (at != null && at.atZoneSameInstant(zone).toLocalDate().equals(sentOn)) return at;
        }
        return sentOn.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime();
    }

    private void addItems(ClientOffer o, Tender t, ReadyOfferReader.Result read, CompanyProfile p) {
        Map<Long, List<PurchaseOffer>> byLot = requestOffers.offersByLot(t.getId());
        Map<String, MedRegistry> records = records(t.getLots());
        if (read.status() != ReplyParseStatus.PARSED) {
            // PDF, картинка, не разобран: строки — из позиций заявки без цены клиенту (спека §4.6)
            int no = 0;
            for (TenderLot lot : sorted(t.getLots())) {
                ClientOfferItem it = ClientOfferRequestService.item(o, ++no, lot, p, rec(lot, records));
                PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(b -> ClientOfferRequestService.link(it, b));
                o.getItems().add(it);
            }
            return;
        }
        List<OfferFileLine> lines = read.lines();
        List<OfferFileLine> items = lines.stream().filter(l -> l.kind() == ClientOfferItemKind.ITEM).toList();
        Map<OfferFileLine, TenderLot> links = new IdentityHashMap<>();
        if (t.getLots().isEmpty() && !items.isEmpty()) {
            privateRequests.addLines(t.getId(), items.stream().map(ReadyOfferService::input).toList(), LinesMode.APPEND);
            List<TenderLot> created = sorted(t.getLots());
            for (int i = 0; i < items.size() && i < created.size(); i++) links.put(items.get(i), created.get(i));
        } else {
            links.putAll(match(items, t.getLots()));
        }
        int no = 0;
        for (OfferFileLine l : lines) {
            no++;
            if (l.kind() != ClientOfferItemKind.ITEM) {
                o.getItems().add(ClientOfferItem.builder().offer(o).lineNo(no).kind(l.kind()).name(l.name()).build());
                continue;
            }
            TenderLot lot = links.get(l);
            ClientOfferItem it = lot != null
                    ? ClientOfferRequestService.item(o, no, lot, p, rec(lot, records))
                    : ClientOfferItem.builder().offer(o).lineNo(no).kind(ClientOfferItemKind.ITEM).vatRate(p.getVatDefault()).build();
            it.setName(l.name());
            it.setModel(l.model());
            if (l.brand() != null) it.setManufacturer(l.brand());
            it.setQuantity(l.quantity());
            it.setUnit(l.unit());
            it.setPriceOverride(l.price());
            OfferFileLines.VatPick vat = OfferFileLines.vat(l.vat(), p.getVatRates());
            if (vat.found()) it.setVatRate(vat.rate());
            if (lot != null) PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(b -> ClientOfferRequestService.link(it, b));
            o.getItems().add(it);
        }
    }

    /** Строки-позиции КП ↔ позиции заявки: только уверенные пары (AUTO). */
    static Map<OfferFileLine, TenderLot> match(List<OfferFileLine> items, Collection<TenderLot> lots) {
        Map<OfferFileLine, TenderLot> out = new IdentityHashMap<>();
        if (items.isEmpty() || lots.isEmpty()) return out;
        Map<Long, TenderLot> byId = new HashMap<>();
        List<ReplyMatcher.Position> positions = new ArrayList<>();
        for (TenderLot l : lots) {
            byId.put(l.getId(), l);
            String text = String.join(" ", Objects.toString(l.getEquipName(), ""), Objects.toString(l.getManufact(), "")).trim();
            positions.add(new ReplyMatcher.Position(l.getId(), l.getLotNumber() == null ? 0 : l.getLotNumber(), text, l.getQuantity()));
        }
        List<ReplyMatcher.Row> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) rows.add(new ReplyMatcher.Row(i, i, items.get(i).matchText(), items.get(i).quantity()));
        for (ReplyMatcher.Match m : ReplyMatcher.match(positions, rows)) {
            if (m.kind() == ReplyMatcher.Kind.AUTO) out.put(items.get(m.fileRow()), byId.get(m.itemId()));
        }
        return out;
    }

    /** Новая позиция заявки из строки КП: дробное количество — 1, как на других путях (решение при планировании 3). */
    private static PrivateRequestLineInput input(OfferFileLine l) {
        BigDecimal q = l.quantity();
        Integer qty = q != null && q.stripTrailingZeros().scale() <= 0 && q.compareTo(BigDecimal.valueOf(1_000_000)) <= 0
                ? q.intValue() : null;
        return PrivateRequestService.input(l.name(), l.brand(), qty, l.unit(), null, null);
    }

    private static List<TenderLot> sorted(Collection<TenderLot> lots) {
        List<TenderLot> out = new ArrayList<>(lots);
        out.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
                .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId()));
        return out;
    }

    private Map<String, MedRegistry> records(Collection<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegStatus() == LineRegStatus.CONFIRMED && l.getRegNumber() != null && !l.getRegNumber().isBlank()) {
                numbers.add(l.getRegNumber().trim());
            }
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    private static MedRegistry rec(TenderLot lot, Map<String, MedRegistry> records) {
        return lot.getRegNumber() == null ? null : records.get(lot.getRegNumber().trim());
    }

    /** Пометка разбора: причина сбоя или чужая валюта цен. */
    private static String note(ReadyOfferReader.Result read, Market market) {
        if (read.foreignCurrency() == null) return read.note();
        String what = MoneyText.MIXED.equals(read.foreignCurrency()) ? "в разных валютах" : "в " + MoneyText.label(read.foreignCurrency());
        return "Цены в файле — " + what + ", а КП — в " + (market == Market.RF ? "рублях" : "тенге") + ": впишите цены по документу";
    }

    static String aisText(ClientOffer o) {
        String label = OfferLabels.of(o);
        return o.getStatus() == ClientOfferStatus.DRAFT
                ? "Это " + label + " из конструктора — отметьте его отправленным"
                : "Это " + label + " из конструктора — оно уже в сделке";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
