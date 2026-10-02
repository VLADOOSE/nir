package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.document.KpFonts;
import com.vladoose.nir.service.document.KpPdfRenderer;
import com.vladoose.nir.service.document.KpPreviewRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KpPreviewRendererTest {

    private final KpPreviewRenderer preview = new KpPreviewRenderer();

    private byte[] twoPagePdf() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 80; i++) rows.append("<tr><td>").append(i).append("</td></tr>");
        return new KpPdfRenderer(new KpFonts()).render(KpPdfRendererTest.html("<table>" + rows + "</table>"));
    }

    @Test
    void rendersEveryPageToPng() throws Exception {
        List<byte[]> pages = preview.pages(twoPagePdf(), 10);
        assertThat(pages).hasSizeGreaterThan(1);
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
        // A4 = 210 мм при 110 dpi ≈ 909 px
        assertThat(first.getWidth()).isBetween(890, 930);
    }

    @Test
    void limitsPageCount() {
        assertThat(preview.pages(twoPagePdf(), 1)).hasSize(1);
    }
}
