package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.dto.response.ClientOfferListItemResponse;
import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferItem;
import com.vladoose.nir.entity.ClientOfferOrigin;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ClientOfferMapper {

    private final TenderRepository tenders;
    private final MedRegistryRepository registry;
    private final PriceRequestItemRepository priceItems;
    private final ClientOfferFileRepository files;
    private final CurrencyRates rates;

    public ClientOfferMapper(TenderRepository tenders, MedRegistryRepository registry, PriceRequestItemRepository priceItems,
                             ClientOfferFileRepository files, CurrencyRates rates) {
        this.tenders = tenders;
        this.registry = registry;
        this.priceItems = priceItems;
        this.files = files;
        this.rates = rates;
    }

    public ClientOfferResponse toResponse(ClientOffer o, OfferCalculation calc, String fileBaseName) {
        ClientOfferResponse r = new ClientOfferResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
        r.setOrigin(o.getOrigin());
        if (o.getOrigin() == ClientOfferOrigin.FILE && o.getId() != null) {
            r.setFile(files.findMetaByOfferIds(List.of(o.getId())).stream().findFirst().orElse(null));
        }
        r.setOfferDate(o.getOfferDate());
        r.setStatus(o.getStatus());
        r.setFacilityId(o.getFacility() == null ? null : o.getFacility().getId());
        r.setFacilityName(o.getFacility() == null ? null : o.getFacility().getName());
        r.setRecipient(o.getRecipient());
        r.setTenderId(o.getTenderId());
        r.setTenderNumber(o.getTenderId() == null ? null
                : tenders.findById(o.getTenderId()).map(Tender::getTenderNumber).orElse(null));
        r.setTitle(o.getTitle());
        r.setSubject(o.getSubject());
        r.setIntro(o.getIntro());
        r.setVatEnabled(o.isVatEnabled());
        r.setDefaultMarkupPct(o.getDefaultMarkupPct());
        r.setRounding(o.getRounding());
        r.setColumns(o.getTableColumns());
        r.setDetailsInName(o.isDetailsInName());
        r.setTerms(o.getTerms());
        r.setTermsStyle(o.getTermsStyle());
        r.setShowAmountInWords(o.isShowAmountInWords());
        r.setShowVatBreakdown(o.isShowVatBreakdown());
        r.setLandscape(o.isLandscape());
        r.setSignoff(o.getSignoff());
        r.setSignoffContacts(o.isSignoffContacts());
        r.setWithStamp(o.isWithStamp());
        r.setInternalNote(o.getInternalNote());
        r.setVersion(o.getVersion());
        r.setCurrency(o.getMarket().currencyCode());
        // запись реестра для № подтверждённых и подсказанных строк — одним запросом (метка показывает запись и срок)
        Set<String> numbers = o.getItems().stream()
                .filter(it -> it.getRegNumber() != null && (it.getRegistrationStatus() == OfferRegistrationStatus.CONFIRMED
                        || it.getRegistrationStatus() == OfferRegistrationStatus.SUGGESTED))
                .map(ClientOfferItem::getRegNumber).collect(Collectors.toSet());
        Map<String, MedRegistry> records = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) records.put(m.getRegNumber(), m);
        // ответы поставщиков, из которых закупка: 💡 и ссылка на экран сверки — одним запросом
        Set<Long> links = o.getItems().stream().map(ClientOfferItem::getPriceRequestItemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PriceRequestItem> answers = new HashMap<>();
        if (!links.isEmpty()) for (PriceRequestItem pi : priceItems.findAllById(links)) answers.put(pi.getId(), pi);
        List<ClientOfferItemDto> items = new ArrayList<>();
        for (int i = 0; i < o.getItems().size(); i++) {
            ClientOfferItem it = o.getItems().get(i);
            ClientOfferItemDto d = toItemDto(it, calc.items().get(i));
            MedRegistry rec = it.getRegNumber() == null ? null : records.get(it.getRegNumber());
            d.setRegistry(rec == null ? null : PrivateRequestViewBuilder.candidate(rec));
            PriceRequestItem pi = it.getPriceRequestItemId() == null ? null : answers.get(it.getPriceRequestItemId());
            d.setPurchaseUnverified(pi != null && pi.isResponseAuto());
            d.setReplyRequestId(pi == null ? null : pi.getPriceRequest().getId());
            items.add(d);
        }
        r.setItems(items);
        r.setTotals(calc.totals());
        r.setFileBaseName(fileBaseName);
        r.setCreatedBy(o.getCreatedBy());
        r.setCreatedAt(o.getCreatedAt());
        r.setUpdatedAt(o.getUpdatedAt());
        r.setSentAt(o.getSentAt());
        r.setOurRates(rates.ours(o.getMarket()));
        Map<String, ClientOfferResponse.TodayRate> today = new LinkedHashMap<>();
        rates.today(o.getMarket()).forEach((c, t) -> today.put(c, new ClientOfferResponse.TodayRate(t.rate(), t.date())));
        r.setTodayRates(today);
        return r;
    }

    public ClientOfferItemDto toItemDto(ClientOfferItem it, ItemCalc calc) {
        ClientOfferItemDto d = new ClientOfferItemDto();
        d.setId(it.getId());
        d.setKey(it.getClientKey() != null ? it.getClientKey() : "i" + it.getId());
        d.setLineNo(it.getLineNo());
        d.setKind(it.getKind());
        d.setName(it.getName());
        d.setModel(it.getModel());
        d.setManufacturer(it.getManufacturer());
        d.setCountry(it.getCountry());
        d.setUnit(it.getUnit());
        d.setQuantity(it.getQuantity());
        d.setPurchasePrice(it.getPurchasePrice());
        d.setPurchaseCurrency(it.getPurchaseCurrency());
        d.setPurchaseOriginal(it.getPurchaseOriginal());
        d.setPurchaseRate(it.getPurchaseRate());
        d.setPurchaseVatSame(it.isPurchaseVatSame());
        d.setPurchaseVatRate(it.getPurchaseVatRate());
        d.setSupplierName(it.getSupplierName());
        d.setMarkupPct(it.getMarkupPct());
        d.setPriceOverride(it.getPriceOverride());
        d.setVatRate(it.getVatRate());
        d.setRegistrationStatus(it.getRegistrationStatus());
        d.setRegistrationText(it.getRegistrationText());
        d.setRegNumber(it.getRegNumber());
        d.setSuggestionScore(it.getSuggestionScore());
        d.setMedEquipmentId(it.getMedEquipmentId());
        d.setNote(it.getNote());
        d.setTenderLotId(it.getTenderLotId());
        d.setPriceRequestItemId(it.getPriceRequestItemId());
        d.setDistributorId(it.getDistributorId());
        d.setCalc(calc);
        return d;
    }

    /** Журнал: номера заявок — одним запросом на всю страницу (без N+1). */
    public List<ClientOfferListItemResponse> toListItems(List<ClientOffer> list) {
        Set<Long> ids = list.stream().map(ClientOffer::getTenderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> numbers = new HashMap<>();
        if (!ids.isEmpty()) for (Object[] row : tenders.findNumbersByIds(ids)) numbers.put((Long) row[0], (String) row[1]);
        return list.stream().map(o -> {
            ClientOfferListItemResponse r = toListItem(o);
            r.setTenderNumber(o.getTenderId() == null ? null : numbers.get(o.getTenderId()));
            return r;
        }).toList();
    }

    public ClientOfferListItemResponse toListItem(ClientOffer o) {
        ClientOfferListItemResponse r = new ClientOfferListItemResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
        r.setOrigin(o.getOrigin());
        r.setOfferDate(o.getOfferDate());
        r.setStatus(o.getStatus());
        r.setClientName(ClientOfferService.clientName(o));
        r.setItemCount(o.getItemCount());
        r.setTotalAmount(o.getTotalAmount());
        r.setCurrency(o.getMarket().currencyCode());
        r.setTenderId(o.getTenderId());
        r.setUpdatedAt(o.getUpdatedAt());
        return r;
    }
}
