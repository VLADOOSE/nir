package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.MedRegistry;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** РУ из текста прайса (спека §5.3): «РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК-МТ-5No017589 (бессрочно)» → «РК-МТ-5№017589»; «Нет РУ…». */
public final class PriceListRegs {

    private static final Pattern NUMBER = Pattern.compile("(?iu)РК[\\s-]*(?:МИ\\s*\\(МТ\\)|МТ)[\\s-]*(\\d?)\\s*(?:№|No\\.?|N)\\s*(\\d{4,7})");
    private static final Pattern NONE = Pattern.compile("(?iu)нет\\s+(?:ру|рег|сертифик)|не\\s+является\\s+медицинск|сертификаты\\s+не\\s+имеются|нет\\s+сертификатов");

    private PriceListRegs() {}

    /** Номер в записи реестра: «РК МИ (МТ)-0№024573», «РК-МТ-5№021045»; не нашёлся — null. */
    public static String number(String text) {
        if (text == null) return null;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return null;
        boolean mi = m.group().toUpperCase().contains("МИ");
        return (mi ? "РК МИ (МТ)-" : "РК-МТ-") + m.group(1) + "№" + m.group(2);
    }

    /** «Нет РУ в Казахстане», «не является медицинским оборудованием», «Сертификаты не имеются». */
    public static boolean none(String text) {
        return text != null && NONE.matcher(text).find();
    }

    /**
     * Статус РУ строки для экрана и подсказок: NONE — «нет РУ» по тексту; номера нет — null; по реестру — ACTIVE (бессрочно,
     * без срока или срок не раньше today) / EXPIRED / NOT_FOUND.
     */
    public static String status(boolean noReg, String number, MedRegistry rec, LocalDate today) {
        if (noReg) return "NONE";
        if (number == null) return null;
        if (rec == null) return "NOT_FOUND";
        return Boolean.TRUE.equals(rec.getUnlimited()) || rec.getExpirationDate() == null || !rec.getExpirationDate().isBefore(today)
                ? "ACTIVE" : "EXPIRED";
    }
}
