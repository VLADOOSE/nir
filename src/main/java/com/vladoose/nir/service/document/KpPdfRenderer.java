package com.vladoose.nir.service.document;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Level;

/**
 * HTML → PDF (openhtmltopdf на PDFBox 3). Наружу рендерер не ходит: разрешены только data:-картинки, которые
 * вставили мы сами (спека client-kp-constructor §6.4) — ни сети, ни file://, даже если в текст КП когда-нибудь
 * просочится разметка. HTML разбирает jsoup (HTML5, сущности вроде &nbsp;) → W3C DOM.
 */
@Component
public class KpPdfRenderer {

    static {
        // по INFO-строке на каждую сборку в лог не нужно — оставляем предупреждения
        for (String logger : XRLog.listRegisteredLoggers()) XRLog.setLevel(logger, Level.WARNING);
    }

    private final KpFonts fonts;

    public KpPdfRenderer(KpFonts fonts) {
        this.fonts = fonts;
    }

    public byte[] render(String html) {
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(Jsoup.parse(html));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        fonts.register(builder);
        builder.useExternalResourceAccessControl(KpPdfRenderer::allowed, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
        builder.withW3cDocument(dom, "/");
        builder.toStream(out);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF не собран", e);
        }
        return out.toByteArray();
    }

    static boolean allowed(String uri, ExternalResourceType type) {
        return uri != null && uri.startsWith("data:");
    }
}
