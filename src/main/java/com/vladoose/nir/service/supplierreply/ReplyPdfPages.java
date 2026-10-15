package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.document.KpPreviewRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Страницы PDF ответа поставщика картинками (спека §4.1): по одной, по запросу, кеш потоков — во временных файлах, а не
 * в куче (на проде -Xmx1g; сканы с крупными картинками). Показываются первые MAX_PAGES страниц.
 */
@Component
public class ReplyPdfPages {

    public static final int MAX_PAGES = 30;

    public int pageCount(byte[] pdf) throws IOException {
        try (PDDocument d = load(pdf)) {
            return d.getNumberOfPages();
        }
    }

    public byte[] page(byte[] pdf, int index) throws IOException {
        try (PDDocument d = load(pdf)) {
            if (index < 0 || index >= Math.min(d.getNumberOfPages(), MAX_PAGES)) {
                throw new NotFoundException("Страницы " + (index + 1) + " нет — показываются первые " + MAX_PAGES);
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new PDFRenderer(d).renderImageWithDPI(index, KpPreviewRenderer.DPI, ImageType.RGB), "png", png);
            return png.toByteArray();
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        return Loader.loadPDF(pdf, "", null, null, IOUtils.createTempFileOnlyStreamCache());
    }
}
