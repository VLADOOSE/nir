package com.vladoose.nir.service.document;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Base64;
import java.util.Locale;

/**
 * KpDocument → HTML по шаблону templates/kp/offer.html. Весь текст — через th:text (экранируется); картинки —
 * data:-URI (единственное, что разрешено грузить рендереру PDF). Положение печати — от начала блока подписи.
 */
@Component
public class KpHtmlRenderer {

    /** Сдвиг печати от левого верхнего угла блока «С уважением…», мм: центр печати ложится на должность и линию. */
    static final int STAMP_LEFT_MM = 22;
    static final int STAMP_TOP_MM = -4;

    private final ITemplateEngine engine;

    public KpHtmlRenderer(ITemplateEngine engine) {
        this.engine = engine;
    }

    public String render(KpDocument doc) {
        Context ctx = new Context(Locale.forLanguageTag("ru"));
        ctx.setVariable("doc", doc);
        ctx.setVariable("pageCss", pageCss(doc.landscape()));
        ctx.setVariable("logo", dataUri(doc.letterhead().logoPng()));
        ctx.setVariable("signature", dataUri(doc.signoff().signaturePng()));
        String stamp = dataUri(doc.signoff().stampPng());
        ctx.setVariable("stamp", stamp);
        ctx.setVariable("stampStyle", "width: " + doc.signoff().stampSizeMm() + "mm; left: " + STAMP_LEFT_MM
                + "mm; top: " + STAMP_TOP_MM + "mm;");
        ctx.setVariable("signoffStyle", stamp == null ? null : signoffStyle(doc.signoff().stampSizeMm()));
        return engine.process("kp/offer", ctx);
    }

    /**
     * Блок подписи — не ниже низа печати (+1 мм на округление). Печать свисает ниже текста подписи, а блок
     * с page-break-inside: avoid переносит на новый лист только себя: печать, не влезшую на лист, openhtmltopdf уносил
     * за обрыв страницы, где она не рисовалась, — у подписи внизу листа печати не было, хотя галочка стоит.
     */
    static String signoffStyle(int stampSizeMm) {
        return "min-height: " + Math.max(0, STAMP_TOP_MM + stampSizeMm + 1) + "mm;";
    }

    static String pageCss(boolean landscape) {
        return landscape
                ? "@page { size: A4 landscape; margin: 12mm 15mm 12mm 15mm; }"
                : "@page { size: A4; margin: 15mm 12mm 15mm 20mm; }";
    }

    static String dataUri(byte[] png) {
        return png == null || png.length == 0 ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
