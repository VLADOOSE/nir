package com.vladoose.nir.service.document;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.CompanyLines;
import com.vladoose.nir.service.RfqTexts;
import com.vladoose.nir.service.offer.OfferColumnKey;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Запрос коммерческого предложения на бланке (спека 2026-10-12 §6.4) → KpDocument для KpDocxRenderer: тот же бланк и
 * геометрия, что у КП клиенту; цен и итогов нет. Подписи нет — «С уважением,» и название компании, печать по отметке. Колонки «Характеристики» и «Регистрация» — только
 * если заполнены хоть у одной позиции; ширины — общий подбор KpDocumentBuilder.fitTable. Клиента, номера заявки и
 * тендера документ не знает вовсе: строку о заказчике ему дают готовой, и только по отметке «Указать клиента».
 */
@Component
public class RfqDocumentBuilder {

    /** Позиция — уже готовым текстом: наименование с брендом, характеристики, количество, ед. изм., регистрация. */
    public record Line(String name, String characteristics, String quantity, String unit, String registration) {}

    /**
     * number — id запроса (метка темы письма); null — документ до отправки, «№ –». clientLine — готовая строка о
     * заказчике или null. replyTo — ящик ответов; replyBy — «Ответить до» или null.
     */
    public record Input(CompanyProfile profile, Long number, LocalDate date, String recipientName, String recipientContact,
                        RfqTexts texts, String clientLine, List<Line> lines, String replyTo, LocalDate replyBy,
                        boolean withStamp) {}

    public KpDocument build(Input in) {
        CompanyProfile p = in.profile();
        boolean specs = in.lines().stream().anyMatch(l -> notBlank(l.characteristics()));
        boolean registration = in.lines().stream().anyMatch(l -> notBlank(l.registration()));

        List<KpDocument.Column> columns = new ArrayList<>();
        columns.add(column(OfferColumnKey.NUM, "№"));
        // ключ решает класс подбора ширины (NAME — остаток, не меньше четверти), подпись — что в колонке: длинным
        // характеристикам — остаток, наименованию — текстовая колонка в 15 %
        if (specs) {
            columns.add(column(OfferColumnKey.MANUFACTURER, "Наименование"));
            columns.add(column(OfferColumnKey.NAME, "Характеристики"));
        } else {
            columns.add(column(OfferColumnKey.NAME, "Наименование"));
        }
        columns.add(column(OfferColumnKey.QTY, "Кол-во"));
        columns.add(column(OfferColumnKey.UNIT, "Ед. изм."));
        if (registration) {
            columns.add(column(OfferColumnKey.REGISTRATION, p.getMarket() == Market.KZ ? "Регистрация в РК" : "Регистрация"));
        }

        List<KpDocument.Row> rows = new ArrayList<>();
        for (int i = 0; i < in.lines().size(); i++) {
            Line l = in.lines().get(i);
            List<List<String>> cells = new ArrayList<>();
            cells.add(List.of(String.valueOf(i + 1)));
            cells.add(CompanyLines.split(l.name()));
            if (specs) cells.add(CompanyLines.split(l.characteristics()));
            cells.add(List.of(l.quantity()));
            cells.add(List.of(l.unit()));
            if (registration) cells.add(CompanyLines.split(l.registration()));
            rows.add(new KpDocument.Row(KpDocument.RowKind.ITEM, cells, columns.size(), List.of()));
        }
        KpDocumentBuilder.TableFit table = KpDocumentBuilder.fitTable(columns, rows, false);

        String company = p.getShortName() == null ? "" : p.getShortName().trim();
        List<String> recipient = new ArrayList<>(CompanyLines.split(in.recipientName()));
        if (notBlank(in.recipientContact())) recipient.add(in.recipientContact().trim());
        String intro = in.texts().intro().replace("{{компания}}", company)
                + (notBlank(in.clientLine()) ? "\n" + in.clientLine().trim() : "");

        return new KpDocument(
                false,
                KpDocumentBuilder.letterhead(p),
                "Исх. № " + (in.number() == null ? DocFormat.DASH : in.number()) + " от " + DocFormat.date(in.date()) + " г.",
                recipient,
                in.texts().title(),
                null,
                intro,
                List.of(),
                table.columns(),
                rows,
                List.of(),
                null,
                terms(in),
                signoff(p, in.withStamp()),
                table.fontPt());
    }

    /** «В предложении просим указать:», пункты «1. …;» (последний — с точкой) и строка ответа. */
    private static List<String> terms(Input in) {
        List<String> out = new ArrayList<>();
        List<String> ask = in.texts().ask();
        if (!ask.isEmpty()) {
            out.add("В предложении просим указать:");
            for (int i = 0; i < ask.size(); i++) {
                out.add((i + 1) + ". " + KpDocumentBuilder.withListItemEnd(ask.get(i), i == ask.size() - 1));
            }
        }
        String to = notBlank(in.replyTo()) ? "на " + in.replyTo().trim() : "ответным письмом";
        out.add("Ответ просим направить " + to + (in.replyBy() == null ? "." : " до " + DocFormat.date(in.replyBy()) + "."));
        return out;
    }

    /**
     * «С уважением,» и название компании — без линии подписи, должности и имени директора: запросу цен подпись не нужна
     * (решение оператора 2026-10-12). Печать — по отметке, рядом с названием (как у КП с подписью «от компании»);
     * картинка подписи не ставится никогда. Контактов нет: строка ответа уже называет ящик приёма.
     */
    private static KpDocument.Signoff signoff(CompanyProfile p, boolean withStamp) {
        String shortName = p.getShortName() == null ? "" : p.getShortName().trim();
        return new KpDocument.Signoff(false, shortName, null, null, null, withStamp ? p.getStampPng() : null,
                p.getStampSizeMm());
    }

    private static KpDocument.Column column(OfferColumnKey key, String label) {
        return new KpDocument.Column(key.name(), label, key.align(), 0, false);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
