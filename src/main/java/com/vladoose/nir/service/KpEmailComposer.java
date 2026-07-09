package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.util.KpToken;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/** Единственное место построения темы/тела письма запроса КП. Брендинг — по рынку заявки (pr.getMarket()). */
@Component
public class KpEmailComposer {

    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Ctx c = ctx(pr);
        String subject = KpToken.subjectToken(pr.getId()) + " " + humanSubject(c.tender, c.isPrivate);
        return new Composed(subject, buildBody(pr, c.market, c.isPrivate, c.tender));
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        Ctx c = ctx(draft);
        return new Composed(humanSubject(c.tender, c.isPrivate), buildBody(draft, c.market, c.isPrivate, c.tender));
    }

    private record Ctx(Tender tender, Market market, boolean isPrivate) {}
    private Ctx ctx(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        boolean isPrivate = tender.getSource() == Source.PRIVATE_REQUEST;
        return new Ctx(tender, market, isPrivate);
    }

    private String humanSubject(Tender tender, boolean isPrivate) {
        String target = isPrivate ? "заявке " + tender.getTenderNumber() : "тендеру № " + tender.getTenderNumber();
        return "Запрос КП по " + target;
    }

    private String buildBody(PriceRequest pr, Market market, boolean isPrivate, Tender tender) {
        Distributor d = pr.getDistributor();
        StringBuilder sb = new StringBuilder();
        String contact = (safe(d.getLastName()) + " " + safe(d.getFirstName())).trim();
        if (!contact.isBlank()) {
            sb.append("Уважаемый(ая) ").append(contact).append("!\n\n");
        } else {
            sb.append("Здравствуйте!\n\n");
        }
        sb.append(market.companyShortName())
          .append(" просит предоставить коммерческое предложение по позициям ")
          .append(isPrivate ? "заявки " + tender.getTenderNumber() : "тендера № " + tender.getTenderNumber())
          .append(":\n\n");

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
        sb.append("\n");
        if (tender.getDeadline() != null) {
            sb.append("Приём заявок до ").append(DATE.format(tender.getDeadline())).append(".\n");
        }
        String link = announceLink(tender, market);
        if (link != null) {
            sb.append("Объявление: ").append(link).append("\n");
        }
        sb.append("\nПросим указать: цену за единицу, № регистрационного удостоверения (")
          .append(market == Market.KZ ? "НЦЭЛС РК" : "Росздравнадзора")
          .append(") на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n");
        sb.append("С уважением,\n").append(market.companyShortName()).append("\n\n");
        sb.append("Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.");
        return sb.toString();
    }

    private String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по ссылке на объявление)";
    }

    private String announceLink(Tender t, Market market) {
        if (market == Market.KZ) {
            if (t.getSourceExtId() == null) return null;
            // sourceExtId несёт полный номер объявления с суффиксом лота (17274756-1);
            // страница объявления адресуется числовым id до дефиса.
            String announceId = t.getSourceExtId().replaceFirst("-.*$", "");
            return "https://goszakup.gov.kz/ru/announce/index/" + announceId;
        }
        if (t.getSource() == Source.PRIVATE_REQUEST) return null;
        return "https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString="
                + URLEncoder.encode(t.getTenderNumber() == null ? "" : t.getTenderNumber(), StandardCharsets.UTF_8);
    }

    private String safe(String s) { return s == null ? "" : s; }
}
