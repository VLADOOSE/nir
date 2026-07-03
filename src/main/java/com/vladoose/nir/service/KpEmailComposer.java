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
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        boolean isPrivate = tender.getSource() == Source.PRIVATE_REQUEST;
        String target = isPrivate
                ? "заявке " + tender.getTenderNumber()
                : "тендеру № " + tender.getTenderNumber();

        String subject = KpToken.subjectToken(pr.getId()) + " Запрос КП по " + target;

        Distributor d = pr.getDistributor();
        StringBuilder sb = new StringBuilder();
        sb.append("Уважаемый(ая) ").append(safe(d.getLastName())).append(" ").append(safe(d.getFirstName())).append("!\n\n");
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
        sb.append("С уважением,\n").append(market.companyShortName());
        return new Composed(subject, sb.toString());
    }

    private String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по ссылке на объявление)";
    }

    private String announceLink(Tender t, Market market) {
        if (market == Market.KZ) {
            return t.getSourceExtId() != null
                    ? "https://goszakup.gov.kz/ru/announce/index/" + t.getSourceExtId()
                    : null;
        }
        if (t.getSource() == Source.PRIVATE_REQUEST) return null;
        return "https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString="
                + URLEncoder.encode(t.getTenderNumber() == null ? "" : t.getTenderNumber(), StandardCharsets.UTF_8);
    }

    private String safe(String s) { return s == null ? "" : s; }
}
