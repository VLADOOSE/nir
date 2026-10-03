package com.vladoose.nir.service.document;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Шрифты PDF КП: Liberation Serif (SIL OFL, лицензия — resources/fonts/LICENSE-LiberationFonts.txt).
 * Метрика совпадает с Times New Roman, поэтому строки в PDF и в Word (где Times New Roman) переносятся одинаково.
 * Знаков ₸ и ₽ в шрифте нет — валюта в документах пишется словами («тг», «тенге», «руб.»).
 */
@Component
public class KpFonts {

    public static final String FAMILY = "Liberation Serif";
    /** Обычное начертание — по нему же KpDocxRenderer меряет строки подписи: метрика та же, что у Times New Roman в Word. */
    static final String REGULAR_FILE = "LiberationSerif-Regular.ttf";

    private final byte[] regular = load(REGULAR_FILE);
    private final byte[] bold = load("LiberationSerif-Bold.ttf");
    private final byte[] italic = load("LiberationSerif-Italic.ttf");
    private final byte[] boldItalic = load("LiberationSerif-BoldItalic.ttf");

    public void register(PdfRendererBuilder builder) {
        builder.useFont(() -> new ByteArrayInputStream(regular), FAMILY, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(bold), FAMILY, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(italic), FAMILY, 400, BaseRendererBuilder.FontStyle.ITALIC, true);
        builder.useFont(() -> new ByteArrayInputStream(boldItalic), FAMILY, 700, BaseRendererBuilder.FontStyle.ITALIC, true);
    }

    static byte[] load(String file) {
        try (InputStream in = KpFonts.class.getResourceAsStream("/fonts/kp/" + file)) {
            if (in == null) throw new IllegalStateException("Нет шрифта в classpath: /fonts/kp/" + file);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
