package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.EmailTemplateRequest;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Шаблон письма КП и тексты запроса на бланке для активного рынка (MarketContext). Дефолты — KpEmailComposer и RfqTexts.
 */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    /** Сохранённое рынка (по каждому полю — или зашитое). */
    public EmailTemplateResponse current() {
        Market market = MarketContext.get();
        Optional<EmailTemplate> t = repository.findByMarket(market);
        RfqTexts rfq = RfqTexts.of(t.orElse(null), market);
        return t.map(x -> response(market, x.getSubjectTemplate(), x.getBodyTemplate(), x.getUpdatedAt(), List.of(), rfq))
                .orElseGet(() -> response(market, KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null,
                        List.of(), rfq));
    }

    /** Зашитое (для «Сбросить»). */
    public EmailTemplateResponse defaults() {
        Market market = MarketContext.get();
        return response(market, KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of(),
                RfqTexts.of(null, market));
    }

    @Transactional
    public EmailTemplateResponse save(EmailTemplateRequest r) {
        Market market = MarketContext.get();
        EmailTemplate t = repository.findByMarket(market).orElseGet(EmailTemplate::new);
        t.setMarket(market);
        t.setSubjectTemplate(r.getSubject());
        t.setBodyTemplate(r.getBody());
        // не прислано (старый экран) — не меняется; совпадает с зашитым — не хранится (RfqTexts.custom)
        if (r.getRfqTitle() != null) t.setRfqTitle(RfqTexts.custom(r.getRfqTitle(), RfqTexts.DEFAULT_TITLE));
        if (r.getRfqIntro() != null) t.setRfqIntro(RfqTexts.custom(r.getRfqIntro(), RfqTexts.DEFAULT_INTRO));
        if (r.getRfqAsk() != null) t.setRfqAsk(RfqTexts.custom(r.getRfqAsk(), RfqTexts.defaultAsk(market)));
        if (r.getRfqClientLine() != null) {
            t.setRfqClientLine(RfqTexts.custom(r.getRfqClientLine(), RfqTexts.DEFAULT_CLIENT_LINE));
        }
        t.setUpdatedAt(OffsetDateTime.now());
        repository.save(t);
        List<String> warnings = new ArrayList<>();
        if (r.getBody() == null || !r.getBody().contains("{{позиции}}")) warnings.add("no-positions");
        return response(market, t.getSubjectTemplate(), t.getBodyTemplate(), t.getUpdatedAt(), warnings,
                RfqTexts.of(t, market));
    }

    private static EmailTemplateResponse response(Market market, String subject, String body, OffsetDateTime updatedAt,
                                                  List<String> warnings, RfqTexts rfq) {
        return new EmailTemplateResponse(market.name(), subject, body, updatedAt, warnings, rfq.title(), rfq.intro(),
                String.join("\n", rfq.ask()), rfq.clientLine());
    }
}
