package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.KpToken;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Построение темы/тела письма КП из редактируемого шаблона рынка (email_template) с фолбэком на
 * зашитый дефолт. Плейсхолдеры: {{приветствие}} {{компания}} {{позиции}} {{дедлайн}} {{реестр}} {{заказчик}} {{вложение}}.
 * Письмо НЕ раскрывает конкретный тендер/заявку (нет номера и ссылки на объявление) — решение
 * оператора 2026-07-09; клиент заявки — только строкой {{заказчик}} у запроса с отметкой «Указать клиента»
 * (спека 2026-10-12 §6.2). Токен [КП-id] в теме — серверный, в шаблон не входит.
 */
@Component
public class KpEmailComposer {

    /**
     * Косметическая обрезка длинного ТЗ — анти-лик §9 на ней БОЛЬШЕ НЕ ДЕРЖИТСЯ, лимит можно менять
     * свободно. Коммерческий блок ТЗ СК-Фармации (условия/срок поставки, место дислокации, имя и
     * адрес заказчика) режет структурно {@code LotDescriptiveText.requirementsForEmail} — от первого
     * якоря до конца текста.
     * <p>Прежняя редакция комментария называла запас в 2528 символов: цифра снята с СЫРОГО ТЗ, а
     * обрезка видит санитизированное (срез шапки сдвигает текст влево) — там первый жёсткий якорь
     * стоял на 2459-м символе в 39/39. То есть запас был 2.05× и держался свойством ДАННЫХ (длиной
     * таблицы требований), а не кода: лот с тремя критериями вместо пятнадцати отправил бы имя
     * больницы поставщику, и ничто бы это не поймало. Поэтому запас заменён срезом хвоста.
     */
    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DEFAULT_SUBJECT = "Запрос коммерческого предложения";
    public static final String DEFAULT_BODY =
            "{{приветствие}}\n\n" +
            "{{компания}} просит предоставить коммерческое предложение по следующим позициям:\n\n" +
            "{{позиции}}\n" +
            "{{заказчик}}{{вложение}}{{дедлайн}}Просим указать: цену за единицу, № регистрационного удостоверения " +
            "на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n" +
            "С уважением,\n{{компания}}\n\n" +
            "Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.";

    /** Строка о вложении (спека §6.3) — только когда к письму приложен запрос на бланке. */
    public static final String ATTACHMENT_LINE = "Запрос на бланке компании — во вложении.";
    private static final String POSITIONS = "{{позиции}}", CLIENT = "{{заказчик}}", ATTACHMENT = "{{вложение}}";

    private final EmailTemplateRepository templateRepository;
    private final EmailTemplateRenderer renderer;

    public KpEmailComposer(EmailTemplateRepository templateRepository, EmailTemplateRenderer renderer) {
        this.templateRepository = templateRepository;
        this.renderer = renderer;
    }

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Composed c = build(pr);
        return new Composed(KpToken.subjectToken(pr.getId()) + " " + c.subject(), c.body());
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        return build(draft);
    }

    private Composed build(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        Optional<EmailTemplate> saved = templateRepository.findByMarket(market);
        String subject = saved.map(EmailTemplate::getSubjectTemplate).filter(s -> !s.isBlank()).orElse(DEFAULT_SUBJECT);
        String body = saved.map(EmailTemplate::getBodyTemplate).filter(s -> !s.isBlank()).orElse(DEFAULT_BODY);
        String client = pr.isDiscloseClient()
                ? RfqClientLine.render(RfqTexts.of(saved.orElse(null), market).clientLine(), pr.getTender(),
                        market.companyShortName())
                : null;
        String attachment = pr.isWithDocument() ? ATTACHMENT_LINE : null;
        Map<String, String> vars = vars(pr);
        vars.put("заказчик", client == null ? "" : client + "\n\n");
        vars.put("вложение", attachment == null ? "" : attachment + "\n\n");
        String fullBody = withMissingLines(body, client != null, attachment != null);
        return new Composed(renderer.render(subject, vars), renderer.render(fullBody, vars));
    }

    /**
     * Свой шаблон оператора, сохранённый до этапа 2, не знает {{заказчик}} и {{вложение}}: без них клиент, указанный
     * поставщику, и строка о вложении молча пропали бы из письма. Недостающие — и только когда им есть что сказать —
     * встают отдельным абзацем сразу после {{позиции}} (нет и её — в конец письма). Без клиента и вложения шаблон
     * не меняется. Позиции кончаются переводом строки, а значения строк — пустой строкой, поэтому вставка идёт ПОСЛЕ
     * перевода строки шаблона за {{позиции}} (если он есть): иначе между абзацами вышло бы две пустые строки.
     */
    static String withMissingLines(String body, boolean client, boolean attachment) {
        String missing = (client && !body.contains(CLIENT) ? CLIENT : "")
                + (attachment && !body.contains(ATTACHMENT) ? ATTACHMENT : "");
        if (missing.isEmpty()) return body;
        int at = body.indexOf(POSITIONS);
        if (at < 0) return body.stripTrailing() + "\n\n" + missing;
        int end = at + POSITIONS.length();
        if (body.startsWith("\n", end)) return body.substring(0, end + 1) + missing + body.substring(end + 1);
        return body.substring(0, end) + "\n" + missing + body.substring(end);
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
        v.put("дедлайн", deadlineLine(pr, tender));
        return v;
    }

    /** «Ответить до» из окна запроса цен важнее срока тендера; нет ни того ни другого — пусто. */
    private static String deadlineLine(PriceRequest pr, Tender tender) {
        LocalDate d = pr.getReplyBy() != null ? pr.getReplyBy() : tender.getDeadline();
        return d != null ? "Просим ответить до " + DATE.format(d) + ".\n\n" : "";
    }

    /** Количество с ед. изм. позиции: «5 упак», без неё — «2 шт.» (как раньше). */
    private static String quantity(Integer qty, TenderLot lot) {
        if (qty == null) return "кол-во уточняется";
        String unit = lot.getUnit();
        return unit == null || unit.isBlank() ? qty + " шт." : qty + " " + unit.trim();
    }

    private String buildPositions(PriceRequest pr) {
        StringBuilder sb = new StringBuilder();
        for (PriceRequestItem it : pr.getItems()) {
            TenderLot lot = it.getTenderLot();
            String qty = quantity(it.getRequestedQuantity(), lot);
            // анти-лик: без «Лот N:» и номера лота — поставщик не должен видеть, что позиция под тендер
            sb.append("— ");
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
                if (lot.getRegStatus() == LineRegStatus.CONFIRMED && lot.getRegNumber() != null) {
                    sb.append(", РУ № ").append(lot.getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
                // санитизируем ТЗ: только тех.характеристики, без goszakup-шапки (номер закупки/лота,
                // места/сроки поставки) — иначе письмо раскрывает тендер поставщику (§9 анти-лик)
                String req = LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec());
                if (req != null && !req.isBlank()) {
                    sb.append("  Требования: ").append(trimSpec(req)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** Обрезка ТЗ — одна для письма и документа на бланке (RfqDocumentService). */
    static String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ " + DocFormat.DASH + " по запросу)";
    }

    private String safe(String s) { return s == null ? "" : s; }
}
