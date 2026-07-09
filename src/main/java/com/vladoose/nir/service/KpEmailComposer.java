package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.util.KpToken;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Построение темы/тела письма КП из редактируемого шаблона рынка (email_template) с фолбэком на
 * зашитый дефолт. Плейсхолдеры: {{приветствие}} {{компания}} {{позиции}} {{дедлайн}} {{реестр}}.
 * Письмо НЕ раскрывает конкретный тендер/заявку (нет номера и ссылки на объявление) — решение
 * оператора 2026-07-09. Токен [КП-id] в теме — серверный, в шаблон не входит.
 */
@Component
public class KpEmailComposer {

    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DEFAULT_SUBJECT = "Запрос коммерческого предложения";
    public static final String DEFAULT_BODY =
            "{{приветствие}}\n\n" +
            "{{компания}} просит предоставить коммерческое предложение по следующим позициям:\n\n" +
            "{{позиции}}\n" +
            "{{дедлайн}}Просим указать: цену за единицу, № регистрационного удостоверения ({{реестр}}) " +
            "на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n" +
            "С уважением,\n{{компания}}\n\n" +
            "Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.";

    private final EmailTemplateRepository templateRepository;
    private final EmailTemplateRenderer renderer;

    public KpEmailComposer(EmailTemplateRepository templateRepository, EmailTemplateRenderer renderer) {
        this.templateRepository = templateRepository;
        this.renderer = renderer;
    }

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Tmpl t = loadTemplate(pr);
        Map<String, String> vars = vars(pr);
        String subject = KpToken.subjectToken(pr.getId()) + " " + renderer.render(t.subject(), vars);
        return new Composed(subject, renderer.render(t.body(), vars));
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        Tmpl t = loadTemplate(draft);
        Map<String, String> vars = vars(draft);
        return new Composed(renderer.render(t.subject(), vars), renderer.render(t.body(), vars));
    }

    private record Tmpl(String subject, String body) {}

    private Tmpl loadTemplate(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        String subj = DEFAULT_SUBJECT, body = DEFAULT_BODY;
        Optional<EmailTemplate> opt = templateRepository.findByMarket(market);
        if (opt.isPresent()) {
            EmailTemplate et = opt.get();
            if (et.getSubjectTemplate() != null && !et.getSubjectTemplate().isBlank()) subj = et.getSubjectTemplate();
            if (et.getBodyTemplate() != null && !et.getBodyTemplate().isBlank()) body = et.getBodyTemplate();
        }
        return new Tmpl(subj, body);
    }

    private Map<String, String> vars(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        Distributor d = pr.getDistributor();
        Map<String, String> v = new HashMap<>();
        String contact = (safe(d.getLastName()) + " " + safe(d.getFirstName())).trim();
        v.put("приветствие", contact.isBlank() ? "Здравствуйте!" : "Уважаемый(ая) " + contact + "!");
        v.put("компания", market.companyShortName());
        v.put("реестр", market == Market.KZ ? "НЦЭЛС РК" : "Росздравнадзора");
        v.put("позиции", buildPositions(pr));
        v.put("дедлайн", tender.getDeadline() != null
                ? "Просим ответить до " + DATE.format(tender.getDeadline()) + ".\n\n" : "");
        return v;
    }

    private String buildPositions(PriceRequest pr) {
        StringBuilder sb = new StringBuilder();
        for (PriceRequestItem it : pr.getItems()) {
            TenderLot lot = it.getTenderLot();
            String qty = it.getRequestedQuantity() != null ? it.getRequestedQuantity() + " шт." : "кол-во уточняется";
            sb.append("— Лот ").append(lot.getLotNumber() != null ? lot.getLotNumber() : "—").append(": ");
            MedEquipment eq = it.getMedEquipment();
            if (eq != null) {
                sb.append(eq.getName()).append(" (").append(eq.getManufact()).append(")");
                if (eq.getRegistration() != null) {
                    sb.append(", РУ № ").append(eq.getRegistration().getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
            } else {
                sb.append(lot.getEquipName());
                if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
                    sb.append(" (").append(lot.getManufact()).append(")");
                }
                sb.append(" — ").append(qty).append("\n");
                if (lot.getRequiredSpec() != null && !lot.getRequiredSpec().isBlank()) {
                    sb.append("  Требования (из ТЗ): ").append(trimSpec(lot.getRequiredSpec())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по ссылке на объявление)";
    }

    private String safe(String s) { return s == null ? "" : s; }
}
