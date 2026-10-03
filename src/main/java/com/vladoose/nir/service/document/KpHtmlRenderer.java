package com.vladoose.nir.service.document;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Locale;

/**
 * KpDocument → HTML по шаблону templates/kp/offer.html. Весь текст — через th:text (экранируется); картинки —
 * data:-URI (единственное, что разрешено грузить рендереру PDF). Печать привязана к началу линии подписи.
 */
@Component
public class KpHtmlRenderer {

    /**
     * Где печать, мм (спека §6.3: «ложится центром у левого края линии подписи»): центр печати — в STAMP_CENTER_X_MM
     * правее начала линии подписи и в STAMP_CENTER_Y_MM ниже её (минус — выше: центр на строке должности). Длина
     * должности не важна — печать держится за линию, а не за блок «С уважением…». У подписи от компании линии нет —
     * её началом служит конец названия компании. Word ставит печать по тем же правилам.
     */
    static final double STAMP_CENTER_X_MM = 0;
    static final double STAMP_CENTER_Y_MM = -4;

    private final ITemplateEngine engine;

    public KpHtmlRenderer(ITemplateEngine engine) {
        this.engine = engine;
    }

    public String render(KpDocument doc) {
        Context ctx = new Context(Locale.forLanguageTag("ru"));
        ctx.setVariable("doc", doc);
        ctx.setVariable("pageCss", pageCss(doc.landscape()));
        ctx.setVariable("tableStyle", "font-size: " + css(doc.tableFontPt()) + "pt;");
        ctx.setVariable("logo", dataUri(doc.letterhead().logoPng()));
        ctx.setVariable("signature", dataUri(doc.signoff().signaturePng()));
        String stamp = dataUri(doc.signoff().stampPng());
        int size = doc.signoff().stampSizeMm();
        ctx.setVariable("stamp", stamp);
        // от низа блока линии (offer.html, .sign-box): низ блока — сама линия
        ctx.setVariable("stampStyle", "width: " + size + "mm; left: " + css(STAMP_CENTER_X_MM - size / 2.0)
                + "mm; bottom: " + css(-(STAMP_CENTER_Y_MM + size / 2.0)) + "mm;");
        ctx.setVariable("signoffStyle", stamp == null ? null : signoffStyle(size));
        return engine.process("kp/offer", ctx);
    }

    /**
     * Запас под печать снизу блока подписи (+1 мм на округление): печать свисает ниже линии подписи на
     * STAMP_CENTER_Y_MM + размер/2, а блок с page-break-inside: avoid переносит на новый лист только себя — печать, не
     * влезшую на лист, openhtmltopdf уносил за обрыв страницы, где она не рисовалась (у подписи внизу листа печати не
     * было, хотя галочка стоит). Запас отсчитывается от низа блока: низ блока не выше линии, где бы она ни оказалась.
     */
    static String signoffStyle(int stampSizeMm) {
        return "padding-bottom: " + css(Math.max(0, STAMP_CENTER_Y_MM + stampSizeMm / 2.0 + 1)) + "mm;";
    }

    /** Лист и его поля — из KpPageGeometry (те же поля у Word): «@page { size: A4; margin: 15mm 12mm 15mm 20mm; }». */
    static String pageCss(boolean landscape) {
        KpPageGeometry.Margins m = KpPageGeometry.margins(landscape);
        return "@page { size: A4" + (landscape ? " landscape" : "") + "; margin: " + mm(m.top()) + " " + mm(m.right()) + " "
                + mm(m.bottom()) + " " + mm(m.left()) + "; }";
    }

    /** Длина для CSS без лишних знаков: «15mm», «12.5mm» — с точкой при любой локали. */
    private static String mm(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + "mm";
    }

    static String dataUri(byte[] png) {
        return png == null || png.length == 0 ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    /** Число для CSS — с точкой при любой локали («9.5», не «9,5»). */
    private static String css(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
