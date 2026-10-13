package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.NewPricesResponse;
import com.vladoose.nir.dto.response.SupplierOfferResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import com.vladoose.nir.util.RegistrationText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * КП клиенту из частной заявки (спека 2026-10-12 §8): черновик — как «+ Новое КП» (номер из счётчика, умолчания
 * «Реквизитов»), плюс клиент, «Кому», ссылка на заявку и строки из её позиций с РУ, ставкой и ценой закупки из ответов
 * поставщиков. КП — снимок: позиции и цены, пришедшие позже, в него сами не попадают.
 */
@Service
public class ClientOfferRequestService {

    private static final Comparator<TenderLot> BY_LINE = Comparator
            .comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
            .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId());

    private final ClientOfferService offers;
    private final ClientOfferRepository repository;
    private final PrivateRequestService privateRequests;
    private final CompanyProfileService profiles;
    private final PriceRequestItemRepository priceItems;
    private final MedRegistryRepository registry;

    public ClientOfferRequestService(ClientOfferService offers, ClientOfferRepository repository,
                                     PrivateRequestService privateRequests, CompanyProfileService profiles,
                                     PriceRequestItemRepository priceItems, MedRegistryRepository registry) {
        this.offers = offers;
        this.repository = repository;
        this.privateRequests = privateRequests;
        this.profiles = profiles;
        this.priceItems = priceItems;
        this.registry = registry;
    }

    @Transactional
    public ClientOffer createFromPrivateRequest(Long tenderId, String author) {
        Tender t = privateRequests.findById(tenderId);   // гард: только частная заявка своего рынка, иначе 404
        ClientOffer o = offers.create(author);
        CompanyProfile p = profiles.forMarket(o.getMarket());
        o.setFacility(t.getFacility());
        o.setRecipient(recipient(t));
        o.setTenderId(t.getId());
        Map<Long, List<PurchaseOffer>> byLot = offersByLot(t.getId());
        Map<String, MedRegistry> records = records(t.getLots());
        int lineNo = 0;
        for (TenderLot lot : t.getLots().stream().sorted(BY_LINE).toList()) {
            ClientOfferItem it = item(o, ++lineNo, lot, p, lot.getRegNumber() == null ? null : records.get(lot.getRegNumber().trim()));
            PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(best -> link(it, best));
            o.getItems().add(it);
        }
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);   // 400 до сохранения, а не ошибка базы (решение плана 12)
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        repository.flush();
        return o;
    }

    /** «Поставщик ▾» (спека §8): ответы с ценой на позицию строки — по последнему от поставщика, от дешёвого. */
    @Transactional(readOnly = true)
    public List<SupplierOfferResponse> offers(Long offerId, Long itemId) {
        ClientOffer o = offers.get(offerId);
        ClientOfferItem it = o.getItems().stream().filter(i -> itemId.equals(i.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Строка КП не найдена: id=" + itemId));
        if (o.getTenderId() == null || it.getTenderLotId() == null) return List.of();
        List<PurchaseOffer> current = offersByLot(o.getTenderId()).getOrDefault(it.getTenderLotId(), List.of());
        if (current.isEmpty()) return List.of();
        BigDecimal min = current.get(0).price();
        return current.stream().map(c -> new SupplierOfferResponse(c.priceRequestItemId(), c.supplierName(), c.price(),
                c.respondedOn(), "ACCEPTED".equals(c.status()), c.price().compareTo(min) == 0,
                Objects.equals(c.priceRequestItemId(), it.getPriceRequestItemId()))).toList();
    }

    /** Что подставить строке: цена и ответ, из которого она. */
    private record Update(ClientOfferItem item, PurchaseOffer offer, BigDecimal price) {}

    /**
     * Строки черновика из заявки, которым есть что подставить (спека §8): пустая закупка, а для позиции теперь есть цена по
     * правилу умолчания, — или цена выбранного ответа изменилась. Сознательно выбранный не самый дешёвый ответ с прежней
     * ценой и ручная закупка (без ссылки) не трогаются; строка без позиции (позицию удалили) — тоже.
     */
    private List<Update> updates(ClientOffer o) {
        if (o.getStatus() != ClientOfferStatus.DRAFT || o.getTenderId() == null) return List.of();
        Map<Long, List<PurchaseOffer>> byLot = offersByLot(o.getTenderId());
        Map<Long, PurchaseOffer> byAnswer = new HashMap<>();
        byLot.values().forEach(list -> list.forEach(c -> byAnswer.put(c.priceRequestItemId(), c)));
        Set<Long> linked = o.getItems().stream().map(ClientOfferItem::getPriceRequestItemId).filter(Objects::nonNull)
                .filter(id -> !byAnswer.containsKey(id)).collect(Collectors.toSet());
        for (PriceRequestItem i : priceItems.findAllById(linked)) {
            if (i.getResponsePrice() != null && i.getTenderLot() != null) byAnswer.put(i.getId(), PurchaseOffer.of(i));
        }
        List<Update> out = new ArrayList<>();
        for (ClientOfferItem it : o.getItems()) {
            if (it.getKind() != ClientOfferItemKind.ITEM) continue;
            if (it.getPurchasePrice() == null) {
                if (it.getTenderLotId() == null) continue;
                PurchaseOffers.best(byLot.getOrDefault(it.getTenderLotId(), List.of()))
                        .ifPresent(best -> out.add(new Update(it, best, scaled(best.price()))));
            } else if (it.getPriceRequestItemId() != null) {
                PurchaseOffer answer = byAnswer.get(it.getPriceRequestItemId());
                if (answer != null && scaled(answer.price()).compareTo(it.getPurchasePrice()) != 0) {
                    out.add(new Update(it, answer, scaled(answer.price())));
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public NewPricesResponse newPrices(Long offerId) {
        ClientOffer o = offers.get(offerId);
        return new NewPricesResponse(updates(o).stream().map(u -> new NewPricesResponse.Line(u.item().getId(),
                u.item().getLineNo(), u.item().getName(), u.item().getPurchasePrice(), u.price(),
                u.offer().supplierName())).toList());
    }

    /** «Подставить»: цены из плашки — в строки черновика; версия растёт, вторая вкладка получит 409 (решение плана 8). */
    @Transactional
    public ClientOffer applyNewPrices(Long offerId) {
        ClientOffer o = offers.get(offerId);
        if (o.getStatus() != ClientOfferStatus.DRAFT) throw new BadRequestException("Новые цены подставляются только в черновик");
        if (o.getTenderId() == null) throw new BadRequestException("КП не из заявки — подставлять нечего");
        for (Update u : updates(o)) link(u.item(), u.offer());
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        o.setUpdatedAt(OffsetDateTime.now());
        repository.flush();
        return o;
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Кандидаты в закупку по позициям заявки (решение плана 2). */
    Map<Long, List<PurchaseOffer>> offersByLot(Long tenderId) {
        Map<Long, List<PurchaseOffer>> raw = new HashMap<>();
        for (PriceRequestItem i : priceItems.findPricedByTenderId(tenderId)) {
            raw.computeIfAbsent(i.getTenderLot().getId(), k -> new ArrayList<>()).add(PurchaseOffer.of(i));
        }
        Map<Long, List<PurchaseOffer>> out = new HashMap<>();
        raw.forEach((lot, lines) -> out.put(lot, PurchaseOffers.current(lines)));
        return out;
    }

    /** «Кому» — печатный текст: учреждение → название клиента → контакт; телефон и «Клиент не указан» — нет (решение плана 1). */
    static String recipient(Tender t) {
        if (t.getFacility() != null && !blank(t.getFacility().getName())) return t.getFacility().getName().trim();
        if (!blank(t.getCustomerName())) return t.getCustomerName().trim();
        if (!blank(t.getContactName())) return t.getContactName().trim();
        return null;
    }

    private Map<String, MedRegistry> records(Collection<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegStatus() == LineRegStatus.CONFIRMED && !blank(l.getRegNumber())) numbers.add(l.getRegNumber().trim());
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    /**
     * Строка КП из позиции: регистрация, производитель и ставка — по метке РУ (решение плана 4 этапа 3; подсказка и
     * «нет в реестре» — с части 3 волны 2).
     */
    static ClientOfferItem item(ClientOffer o, int lineNo, TenderLot lot, CompanyProfile p, MedRegistry rec) {
        Integer qty = lot.getQuantity();
        ClientOfferItem it = ClientOfferItem.builder()
                .offer(o).lineNo(lineNo).kind(ClientOfferItemKind.ITEM)
                .name(lot.getEquipName() == null ? "" : lot.getEquipName().trim())
                .unit(blank(lot.getUnit()) ? "шт" : lot.getUnit().trim())
                .quantity(BigDecimal.valueOf(qty == null || qty <= 0 ? 1 : qty).setScale(3))
                .manufacturer(blank(lot.getManufact()) ? null : lot.getManufact().trim())
                .tenderLotId(lot.getId())
                .build();
        if (lot.getRegStatus() == LineRegStatus.CONFIRMED && !blank(lot.getRegNumber())) {
            it.setRegistrationStatus(OfferRegistrationStatus.CONFIRMED);
            it.setRegNumber(lot.getRegNumber().trim());
            it.setRegistrationText(RegistrationText.of(lot.getRegNumber(), rec));
            if (rec != null && !blank(rec.getProducer())) it.setManufacturer(cut(rec.getProducer(), 500));
            if (rec != null && !blank(rec.getCountry())) it.setCountry(cut(rec.getCountry(), 200));
            it.setVatRate(p.getVatRegistered());
        } else if (lot.getRegStatus() == LineRegStatus.NOT_REQUIRED) {
            it.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
            it.setRegistrationText(ClientOfferService.NOT_REQUIRED_TEXT);
            it.setVatRate(p.getVatNotRegistrable());
        } else if (lot.getRegStatus() == LineRegStatus.SUGGESTED && !blank(lot.getRegNumber())) {
            // подсказку этапа 1 переносим как подсказку: подтвердить её в КП теперь можно (спека 2026-10-13 §2.6)
            it.setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
            it.setRegNumber(lot.getRegNumber().trim());
            it.setSuggestionScore(lot.getRegScore());
            it.setVatRate(p.getVatDefault());
        } else if (lot.getRegStatus() == LineRegStatus.NOT_FOUND) {
            it.setRegistrationStatus(OfferRegistrationStatus.NOT_FOUND);
            it.setVatRate(p.getVatDefault());
        } else {
            it.setVatRate(p.getVatDefault());
        }
        return it;
    }

    static void link(ClientOfferItem it, PurchaseOffer offer) {
        it.setPurchasePrice(offer.price().setScale(2, RoundingMode.HALF_UP));
        it.setSupplierName(cut(offer.supplierName(), 255));
        it.setPriceRequestItemId(offer.priceRequestItemId());
        it.setDistributorId(offer.distributorId());
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
