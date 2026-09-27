package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Заявка сайта → IncomingLead (спека §6.2): тема, позиции с брендом из каталога, статус из истории. */
@Component
public class WestmedLeadMapper {

    private final String siteUrl;

    public WestmedLeadMapper(@Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public IncomingLead fromPriceRequest(WestmedPriceRequest r, WestmedProductLookup lookup) {
        List<IncomingLead.Item> items = new ArrayList<>();
        String subject;
        if (r.productName() != null && !r.productName().isBlank()) {
            subject = "Запрос цены";
            Optional<WestmedProduct> p = lookup.byName(r.productName());
            items.add(new IncomingLead.Item(r.productName().trim(),
                    p.map(WestmedProduct::brandName).orElse(null), 1,
                    p.map(x -> productUrl(x.slug())).orElse(null)));
        } else {
            subject = "Заявка с сайта";   // «Оставить заявку» без товара — суть в тексте клиента
        }
        return build(WestmedKind.PRICE.externalId(r.id()), subject, r.name(), r.phone(), r.email(),
                r.company(), r.message(), items, r.status(), r.createdAt());
    }

    public IncomingLead fromQuoteRequest(WestmedQuoteRequest r, WestmedProductLookup lookup) {
        List<IncomingLead.Item> items = new ArrayList<>();
        for (WestmedQuoteRequest.Item i : r.items() == null ? List.<WestmedQuoteRequest.Item>of() : r.items()) {
            if (i.productName() == null || i.productName().isBlank()) continue;
            Optional<WestmedProduct> p = lookup.bySlug(i.productSlug(), i.productName());
            items.add(new IncomingLead.Item(i.productName().trim(),
                    p.map(WestmedProduct::brandName).orElse(null),
                    i.quantity() != null && i.quantity() > 0 ? i.quantity() : 1,
                    i.productSlug() != null && !i.productSlug().isBlank() ? productUrl(i.productSlug()) : null));
        }
        return build(WestmedKind.QUOTE.externalId(r.id()), "Запрос КП", r.name(), r.phone(), r.email(),
                r.company(), r.message(), items, r.status(), r.createdAt());
    }

    private IncomingLead build(String externalId, String subject, String name, String phone, String email,
                               String company, String message, List<IncomingLead.Item> items,
                               String siteStatus, String createdAt) {
        return new IncomingLead(LeadSources.WESTMED, externalId, LeadChannel.SITE, subject, parseTime(createdAt),
                name, phone, email, company, message, items, initialStatus(siteStatus), siteStatus, null);
    }

    /** Статус сайта при ПЕРВОМ появлении заявки в АИС (дальше мастер статусов — АИС). */
    static LeadStatus initialStatus(String siteStatus) {
        if (siteStatus == null) return LeadStatus.NEW;
        return switch (siteStatus) {
            case "PROCESSED" -> LeadStatus.IN_WORK;
            case "CLOSED" -> LeadStatus.CLOSED;
            default -> LeadStatus.NEW;
        };
    }

    static OffsetDateTime parseTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String productUrl(String slug) {
        return siteUrl + "/product/" + slug;   // next-intl localePrefix "as-needed": ru — без префикса
    }
}
