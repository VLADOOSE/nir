package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadEvent;
import com.vladoose.nir.entity.LeadItem;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class LeadResponseMapper {

    private static final int PREVIEW_ITEMS = 2;
    private static final int PREVIEW_CHARS = 140;

    public LeadListItemResponse toListItem(Lead l) {
        LeadListItemResponse r = new LeadListItemResponse();
        r.setId(l.getId());
        r.setChannel(l.getChannel().name());
        r.setSource(l.getSource());
        r.setSubject(l.getSubject());
        r.setContactName(l.getContactName());
        r.setContactPhone(l.getContactPhone());
        r.setCompany(l.getCompany());
        r.setItemsCount(l.getItems().size());
        r.setItemsPreview(l.getItems().stream().limit(PREVIEW_ITEMS).map(LeadItem::getName).toList());
        r.setMessagePreview(preview(l.getMessage()));
        r.setStatus(l.getStatus().name());
        r.setCloseReason(l.getCloseReason() == null ? null : l.getCloseReason().name());
        r.setReceivedAt(l.getReceivedAt());
        if (l.getPrivateRequest() != null) {
            r.setPrivateRequestId(l.getPrivateRequest().getId());
            r.setPrivateRequestNumber(l.getPrivateRequest().getTenderNumber());
        }
        r.setSyncError(l.getExtSyncError() != null);
        return r;
    }

    public LeadCardResponse toCard(Lead l, List<Lead> samePhone) {
        LeadCardResponse r = new LeadCardResponse();
        r.setId(l.getId());
        r.setChannel(l.getChannel().name());
        r.setSource(l.getSource());
        r.setSubject(l.getSubject());
        r.setContactName(l.getContactName());
        r.setContactPhone(l.getContactPhone());
        r.setPhoneNorm(l.getPhoneNorm());
        r.setContactEmail(l.getContactEmail());
        r.setCompany(l.getCompany());
        r.setMessage(l.getMessage());
        r.setStatus(l.getStatus().name());
        r.setCloseReason(l.getCloseReason() == null ? null : l.getCloseReason().name());
        r.setReceivedAt(l.getReceivedAt());
        if (l.getFacility() != null) {
            r.setFacilityId(l.getFacility().getId());
            r.setFacilityName(l.getFacility().getName());
        }
        if (l.getPrivateRequest() != null) {
            r.setPrivateRequestId(l.getPrivateRequest().getId());
            r.setPrivateRequestNumber(l.getPrivateRequest().getTenderNumber());
        }
        r.setExtStatus(l.getExtStatus());
        r.setExtStatusPending(l.getExtStatusPending());
        r.setExtSyncError(l.getExtSyncError());
        r.setItems(l.getItems().stream().map(LeadResponseMapper::toItem).toList());
        r.setEvents(l.getEvents().stream()
                .sorted(Comparator.comparing(LeadEvent::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LeadEvent::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(LeadResponseMapper::toEvent).toList());
        r.setSamePhone(samePhone.stream().map(this::toRef).toList());
        return r;
    }

    public LeadRefResponse toRef(Lead l) {
        LeadRefResponse r = new LeadRefResponse();
        r.setId(l.getId());
        r.setSubject(l.getSubject());
        r.setSource(l.getSource());
        r.setStatus(l.getStatus().name());
        r.setReceivedAt(l.getReceivedAt());
        return r;
    }

    private static LeadItemDto toItem(LeadItem i) {
        LeadItemDto d = new LeadItemDto();
        d.setId(i.getId());
        d.setName(i.getName());
        d.setBrand(i.getBrand());
        d.setQuantity(i.getQuantity());
        d.setProductUrl(i.getProductUrl());
        return d;
    }

    private static LeadEventResponse toEvent(LeadEvent e) {
        LeadEventResponse r = new LeadEventResponse();
        r.setId(e.getId());
        r.setAt(e.getOccurredAt());
        r.setType(e.getType().name());
        r.setDirection(e.getDirection() == null ? null : e.getDirection().name());
        r.setChannel(e.getChannel() == null ? null : e.getChannel().name());
        r.setAuthor(e.getAuthor());
        r.setBody(e.getBody());
        return r;
    }

    static String preview(String text) {
        if (text == null || text.isBlank()) return null;
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS - 1) + "…";
    }
}
