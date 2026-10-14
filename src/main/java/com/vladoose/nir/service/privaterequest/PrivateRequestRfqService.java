package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.RfqClientLine;
import com.vladoose.nir.service.RfqDocumentService;
import com.vladoose.nir.util.EmailAddresses;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Окно «Запросить цены» частной заявки (спека 2026-10-12 §6.2): каждому поставщику — одно письмо только с его позициями,
 * через единый канал отправки. Сначала проверяется ВЕСЬ запрос — позиции своей заявки, поставщики своего рынка, новые
 * поставщики (название не занято на другом рынке), почта, клиент, дата, печать; потом заводятся новые поставщики и
 * пишется почта в пустые карточки; и только после этого уходят письма: отказ на третьем поставщике не оставит двух
 * отправленных писем без итога.
 */
@Service
public class PrivateRequestRfqService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestSendService sendService;
    private final DistributorService distributorService;
    private final DistributorRepository distributorRepository;
    private final RfqDocumentService documents;

    public PrivateRequestRfqService(PrivateRequestService privateRequestService, PriceRequestSendService sendService,
                                    DistributorService distributorService, DistributorRepository distributorRepository,
                                    RfqDocumentService documents) {
        this.privateRequestService = privateRequestService;
        this.sendService = sendService;
        this.distributorService = distributorService;
        this.distributorRepository = distributorRepository;
        this.documents = documents;
    }

    /** Письмо, которое уйдёт поставщику, — без сохранения запроса (тема без метки). */
    public KpPreviewResponse preview(Long requestId, RfqPreviewRequest req) {
        return sendService.preview(draft(requestId, req, req.isWithDocument()));
    }

    /** Документ на бланке до отправки — Word или PDF: «№ —», номер появится при отправке. */
    public RfqDocumentService.RfqFile document(Long requestId, RfqPreviewRequest req, RfqDocumentService.Format format) {
        PriceRequest draft = draft(requestId, req, true);
        if (draft.isWithStamp()) sendService.requireStamp(draft.getMarket() != null ? draft.getMarket() : MarketContext.get());
        return documents.render(draft, format);
    }

    /** Word до отправки. */
    public RfqDocumentService.RfqFile document(Long requestId, RfqPreviewRequest req) {
        return document(requestId, req, RfqDocumentService.Format.DOCX);
    }

    private PriceRequest draft(Long requestId, RfqPreviewRequest req, boolean withDocument) {
        Tender t = privateRequestService.findById(requestId);
        checkReplyBy(req.getReplyBy());
        checkDisclose(t, req.isDiscloseClient());
        Distributor d;
        if (req.getDistributorId() != null) {
            d = distributor(req.getDistributorId());
        } else if (req.getNewSupplierName() != null && !req.getNewSupplierName().isBlank()) {
            d = Distributor.builder().name(req.getNewSupplierName().trim()).market(MarketContext.get()).build();
        } else {
            throw new BadRequestException("Не указан поставщик");
        }
        return sendService.draft(t.getId(), d, items(lots(t, req.getLotIds())),
                new SendOptions(req.getReplyBy(), withDocument, req.isWithStamp(), req.isDiscloseClient(), null));
    }

    public List<SendResult> send(Long requestId, RfqSendRequest req) {
        Tender t = privateRequestService.findById(requestId);
        if (req.getSuppliers() == null || req.getSuppliers().isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        checkReplyBy(req.getReplyBy());
        // печать ставится в документ: во вложение письма — по «Приложить запрос», у «передам сам» документ есть всегда
        boolean anyManual = req.getSuppliers().stream().anyMatch(RfqSendRequest.Supplier::isManual);
        if (req.isWithStamp() && (req.isWithDocument() || anyManual)) sendService.requireStamp(market(t));

        // 1. весь запрос проверяется до первой записи и первого письма
        Map<String, Plan> plans = new LinkedHashMap<>();
        for (RfqSendRequest.Supplier s : req.getSuppliers()) {
            Target target = target(s);
            if (s.getLotIds() == null || s.getLotIds().isEmpty()) {
                // текст этапа 1 (его держит PrivateRequestRfqTest): окно показывает, у кого именно пусто
                throw new BadRequestException("У поставщика «" + target.name() + "» не выбраны позиции");
            }
            List<TenderLot> lots = lots(t, s.getLotIds());
            checkDisclose(t, s.isDiscloseClient());
            List<String> typed = null;
            if (s.getEmail() != null && !s.isManual()) {   // «передам сам» — почта окна не нужна и в карточку не пишется
                typed = EmailAddresses.parse(s.getEmail());
                EmailAddresses.normalize(s.getEmail());   // длина поля — тоже до записи
            }
            Plan known = plans.get(target.key());
            if (known != null && known.manual() != s.isManual()) {
                throw new BadRequestException("Поставщик «" + target.name() + "» указан дважды — и письмом, и «передам сам»");
            }
            if (known == null) {
                plans.put(target.key(), new Plan(target, new ArrayList<>(lots), typed, s.isDiscloseClient(), s.isManual()));
            } else {
                for (TenderLot l : lots) if (!known.lots().contains(l)) known.lots().add(l);
            }
        }
        // 2. новые поставщики и почта в пустые карточки — до писем
        List<Sending> sendings = new ArrayList<>();
        for (Plan p : plans.values()) {
            Distributor d = materialize(p);
            sendings.add(new Sending(d, p, emailTo(d, p.typed())));
        }
        // 3. письма — каждому только его позиции
        List<SendResult> results = new ArrayList<>();
        for (Sending s : sendings) {
            SendOptions o = new SendOptions(req.getReplyBy(), req.isWithDocument(), req.isWithStamp(),
                    s.plan().disclose(), s.emailTo(), s.plan().manual());
            results.addAll(sendService.send(t.getId(), List.of(s.distributor().getId()), items(s.plan().lots()), null, null, o));
        }
        return results;
    }

    /** Кому пишем: поставщик справочника (existing) или новый (newName); key — чтобы не писать одному дважды. */
    private record Target(String key, Distributor existing, String newName, boolean rememberBrands) {
        String name() {
            return existing != null ? existing.getName() : newName;
        }
    }

    private record Plan(Target target, List<TenderLot> lots, List<String> typed, boolean disclose, boolean manual) {}

    private record Sending(Distributor distributor, Plan plan, List<String> emailTo) {}

    private Target target(RfqSendRequest.Supplier s) {
        boolean hasId = s.getDistributorId() != null;
        boolean isNew = s.getNewSupplier() != null;
        if (hasId == isNew) throw new BadRequestException("Укажите поставщика из справочника или нового");
        if (hasId) {
            Distributor d = distributor(s.getDistributorId());
            return new Target("d:" + d.getId(), d, null, false);
        }
        String name = s.getNewSupplier().getName() == null ? "" : s.getNewSupplier().getName().trim();
        if (name.isEmpty()) throw new BadRequestException("Укажите название нового поставщика");
        if (name.length() > 255) throw new BadRequestException("Название поставщика — не больше 255 символов");
        Distributor same = existingByName(name);
        boolean remember = s.getNewSupplier().isRememberBrands();
        return same != null
                ? new Target("d:" + same.getId(), same, null, remember)
                : new Target("new:" + name.toLowerCase(Locale.ROOT), null, name, remember);
    }

    /** Название занято на другом рынке — 400; на этом (в любом регистре) — это и есть он, дубля не будет. */
    private Distributor existingByName(String name) {
        for (Object[] row : distributorRepository.findIdAndMarketByName(name)) {
            if (!MarketContext.get().name().equals(String.valueOf(row[1]))) {
                throw new BadRequestException("Поставщик «" + name + "» уже есть на другом рынке — уточните название");
            }
            return distributorService.findById(((Number) row[0]).longValue());
        }
        return null;
    }

    /** Новый — заводится в «Дистрибьюторах»; у существующего с пустой карточкой вписанная почта сохраняется в карточку. */
    private Distributor materialize(Plan p) {
        Target t = p.target();
        List<String> brands = t.rememberBrands() ? brandsOf(p.lots()) : List.of();
        if (t.existing() == null) {
            String email = p.typed() == null ? null : String.join(", ", p.typed());
            return distributorService.save(Distributor.builder().name(t.newName()).email(email)
                    .brands(new ArrayList<>(brands)).build());
        }
        Distributor d = t.existing();
        boolean changed = false;
        if (blank(d.getEmail()) && p.typed() != null && !p.typed().isEmpty()) {
            d.setEmail(String.join(", ", p.typed()));
            changed = true;
        }
        for (String b : brands) {
            if (d.getBrands().stream().noneMatch(x -> x.equalsIgnoreCase(b))) {
                d.getBrands().add(b);
                changed = true;
            }
        }
        return changed ? distributorService.save(d) : d;
    }

    /** null — почта карточки; пустой — без письма (поле очищено); иначе — вписанные адреса, если они не те же, что в карточке. */
    static List<String> emailTo(Distributor d, List<String> typed) {
        if (typed == null) return null;
        if (typed.isEmpty()) return List.of();
        return EmailAddresses.same(String.join(", ", typed), d.getEmail()) ? null : typed;
    }

    private static List<String> brandsOf(List<TenderLot> lots) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TenderLot l : lots) {
            if (!blank(l.getManufact())) out.putIfAbsent(l.getManufact().trim().toLowerCase(Locale.ROOT), l.getManufact().trim());
        }
        return List.copyOf(out.values());
    }

    private static void checkDisclose(Tender t, boolean disclose) {
        if (disclose && RfqClientLine.clientName(t) == null) {
            throw new BadRequestException("Клиент в заявке не указан — указать его поставщику нельзя");
        }
    }

    private Distributor distributor(Long id) {
        Distributor d = distributorService.findById(id);
        if (d.getMarket() != null && d.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Поставщик не найден: " + id);
        }
        return d;
    }

    private static List<TenderLot> lots(Tender t, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        Map<Long, TenderLot> own = new HashMap<>();
        for (TenderLot l : t.getLots()) own.put(l.getId(), l);
        List<TenderLot> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            TenderLot l = own.get(id);
            if (l == null) throw new BadRequestException("Позиция " + id + " не из этой заявки");
            out.add(l);
        }
        return out;
    }

    private static List<SendItem> items(List<TenderLot> lots) {
        return lots.stream().map(l -> new SendItem(l.getId(), null, l.getQuantity())).toList();
    }

    private static void checkReplyBy(LocalDate replyBy) {
        if (replyBy != null && replyBy.isBefore(LocalDate.now(MarketContext.get().zone()))) {
            throw new BadRequestException("Дата «Ответить до» уже прошла");
        }
    }

    private static Market market(Tender t) {
        return t.getMarket() != null ? t.getMarket() : MarketContext.get();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
