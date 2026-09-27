package com.vladoose.nir.integration.lead;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Шов входа обращений (спека §3, §7): так в АИС попадает всё — опрос сайта, ручной ввод,
 * позже вебхуки WhatsApp Business и АТС. externalId = null — у ручных обращений дублей не бывает.
 * initialStatus/extStatus — статус источника при первом появлении (история сайта), author — логин,
 * если обращение внёс человек.
 */
public record IncomingLead(
        String source,
        String externalId,
        LeadChannel channel,
        String subject,
        OffsetDateTime receivedAt,
        String contactName,
        String contactPhone,
        String contactEmail,
        String company,
        String message,
        List<Item> items,
        LeadStatus initialStatus,
        String extStatus,
        String author) {

    public record Item(String name, String brand, int quantity, String productUrl) {}

    public IncomingLead {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
