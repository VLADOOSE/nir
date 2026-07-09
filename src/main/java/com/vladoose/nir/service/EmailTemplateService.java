package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Чтение/сохранение шаблона письма КП для активного рынка (MarketContext). Дефолт — из KpEmailComposer. */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    /** Текущий сохранённый шаблон рынка, либо дефолт, если строки ещё нет. */
    public EmailTemplateResponse current() {
        Market market = MarketContext.get();
        return repository.findByMarket(market)
                .map(t -> new EmailTemplateResponse(market.name(), t.getSubjectTemplate(),
                        t.getBodyTemplate(), t.getUpdatedAt(), List.of()))
                .orElseGet(() -> new EmailTemplateResponse(market.name(),
                        KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of()));
    }

    /** Зашитый дефолт (для «Сбросить»). */
    public EmailTemplateResponse defaults() {
        Market market = MarketContext.get();
        return new EmailTemplateResponse(market.name(),
                KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of());
    }

    @Transactional
    public EmailTemplateResponse save(String subject, String body) {
        Market market = MarketContext.get();
        EmailTemplate t = repository.findByMarket(market).orElseGet(EmailTemplate::new);
        t.setMarket(market);
        t.setSubjectTemplate(subject);
        t.setBodyTemplate(body);
        t.setUpdatedAt(OffsetDateTime.now());
        repository.save(t);
        List<String> warnings = new ArrayList<>();
        if (body == null || !body.contains("{{позиции}}")) warnings.add("no-positions");
        return new EmailTemplateResponse(market.name(), subject, body, t.getUpdatedAt(), warnings);
    }
}
