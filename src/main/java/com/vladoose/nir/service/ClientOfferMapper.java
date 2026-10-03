package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.dto.response.ClientOfferListItemResponse;
import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferItem;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ClientOfferMapper {

    public ClientOfferResponse toResponse(ClientOffer o, OfferCalculation calc, String fileBaseName) {
        ClientOfferResponse r = new ClientOfferResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
        r.setOfferDate(o.getOfferDate());
        r.setStatus(o.getStatus());
        r.setFacilityId(o.getFacility() == null ? null : o.getFacility().getId());
        r.setFacilityName(o.getFacility() == null ? null : o.getFacility().getName());
        r.setRecipient(o.getRecipient());
        r.setTenderId(o.getTenderId());
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
        List<ClientOfferItemDto> items = new ArrayList<>();
        for (int i = 0; i < o.getItems().size(); i++) items.add(toItemDto(o.getItems().get(i), calc.items().get(i)));
        r.setItems(items);
        r.setTotals(calc.totals());
        r.setFileBaseName(fileBaseName);
        r.setCreatedBy(o.getCreatedBy());
        r.setCreatedAt(o.getCreatedAt());
        r.setUpdatedAt(o.getUpdatedAt());
        r.setSentAt(o.getSentAt());
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
        d.setPurchaseVatSame(it.isPurchaseVatSame());
        d.setPurchaseVatRate(it.getPurchaseVatRate());
        d.setSupplierName(it.getSupplierName());
        d.setMarkupPct(it.getMarkupPct());
        d.setPriceOverride(it.getPriceOverride());
        d.setVatRate(it.getVatRate());
        d.setRegistrationStatus(it.getRegistrationStatus());
        d.setRegistrationText(it.getRegistrationText());
        d.setRegNumber(it.getRegNumber());
        d.setNote(it.getNote());
        d.setCalc(calc);
        return d;
    }

    public ClientOfferListItemResponse toListItem(ClientOffer o) {
        ClientOfferListItemResponse r = new ClientOfferListItemResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
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
