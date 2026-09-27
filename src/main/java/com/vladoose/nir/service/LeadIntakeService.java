package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.blankToNull;
import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Единая точка входа обращений (спека §7). Идемпотентна по (source, externalId); рынок — из
 * MarketContext (фоновый вызывающий ставит его ЯВНО, §6 CLAUDE.md). Гонку двух вставок ловит
 * уникальный индекс — вызывающий трактует DataIntegrityViolationException как «уже есть».
 */
@Service
public class LeadIntakeService {

    private final LeadRepository leadRepository;
    private final LeadClientMatcher clientMatcher;

    public LeadIntakeService(LeadRepository leadRepository, LeadClientMatcher clientMatcher) {
        this.leadRepository = leadRepository;
        this.clientMatcher = clientMatcher;
    }

    @Transactional(readOnly = true)
    public boolean isKnown(String source, String externalId) {
        return externalId != null && leadRepository.existsBySourceAndExternalId(source, externalId);
    }

    /** Пусто — такое обращение уже есть. */
    @Transactional
    public Optional<Lead> ingest(IncomingLead in) {
        if (isKnown(in.source(), in.externalId())) {
            return Optional.empty();
        }
        Market market = MarketContext.get();
        String phoneNorm = PhoneNormalizer.normalize(in.contactPhone());
        Lead lead = Lead.builder()
                .market(market)   // пред-штамп (defense-in-depth к листенеру)
                .channel(in.channel())
                .source(in.source())
                .externalId(in.externalId())
                .subject(trunc(in.subject(), 200))
                .contactName(trunc(blankToNull(in.contactName()), 255))
                .contactPhone(trunc(blankToNull(in.contactPhone()), 50))
                .phoneNorm(phoneNorm)
                .contactEmail(trunc(blankToNull(in.contactEmail()), 255))
                .company(trunc(blankToNull(in.company()), 255))
                .message(blankToNull(in.message()))
                .facility(clientMatcher.match(market, phoneNorm, in.contactEmail()))
                .status(in.initialStatus() != null ? in.initialStatus() : LeadStatus.NEW)
                .receivedAt(in.receivedAt() != null ? in.receivedAt() : OffsetDateTime.now())
                .extStatus(in.extStatus())
                .build();
        for (IncomingLead.Item it : in.items()) {
            if (it.name() == null || it.name().isBlank()) continue;
            lead.addItem(trunc(it.name().trim(), 500), trunc(blankToNull(it.brand()), 255),
                    it.quantity(), trunc(blankToNull(it.productUrl()), 500));
        }
        LeadEvent received = lead.addEvent(LeadEventType.RECEIVED, in.author(), receivedText(in, lead.getItems().size()));
        received.setDirection(LeadDirection.IN);
        received.setChannel(in.channel());
        if (in.extStatus() != null && lead.getStatus() != LeadStatus.NEW) {
            lead.addEvent(LeadEventType.STATUS, null,
                    "Импортировано со статусом источника «" + LeadSources.siteStatusLabel(in.extStatus()) + "»");
        }
        return Optional.of(leadRepository.save(lead));
    }

    private static String receivedText(IncomingLead in, int itemCount) {
        String base = in.subject() + (itemCount > 0 ? ", " + itemCount + " поз." : "");
        return LeadSources.MANUAL.equals(in.source()) ? base + " — внесено вручную" : base + " — " + in.source();
    }
}
