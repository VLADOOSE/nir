package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.util.DocFormat;

import java.time.LocalDate;

/** Подпись КП в текстах (лента, этапы, «Дальше», события, имена файлов): у КП из файла номера может не быть. */
public final class OfferLabels {

    private OfferLabels() {}

    /** «КП № 24» или «КП от 24.09.2026». */
    public static String of(ClientOffer o) {
        return of(o.getNumber(), o.getOfferDate());
    }

    /** То же по номеру и дате — без загрузки КП (исполнение сделки). */
    public static String of(Integer number, LocalDate offerDate) {
        return "КП " + (number != null ? "№ " + number : "от " + DocFormat.date(offerDate));
    }

    /** «№ 24» или «от 24.09.2026». */
    public static String numberOrDate(ClientOffer o) {
        return o.getNumber() != null ? "№ " + o.getNumber() : "от " + DocFormat.date(o.getOfferDate());
    }
}
