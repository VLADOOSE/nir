package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.document.KpFonts;
import com.vladoose.nir.service.document.KpPdfRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Рендерер PDF: шрифт с казахскими буквами, повтор шапки таблицы, и главное — никуда не ходит (спека §6.4). */
class KpPdfRendererTest {

    private final KpPdfRenderer renderer = new KpPdfRenderer(new KpFonts());

    @Test
    void rendersCyrillicKazakhAndNumeroSign() {
        byte[] pdf = renderer.render(html("<p>Жауапкершілігі шектеулі серіктестігі — ӘәҒғҚқҢңӨөҰұҮүҺһІі № «West-Med»</p>"));
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(KpTestSupport.text(pdf)).contains("ӘәҒғҚқҢңӨөҰұҮүҺһІі", "№", "«West-Med»", "Жауапкершілігі");
    }

    /** Мутация «снять useExternalResourceAccessControl» или «разрешить всё» роняет этот тест. */
    @Test
    void neverFetchesExternalResources() throws Exception {
        try (KpTestSupport.TrapServer trap = new KpTestSupport.TrapServer()) {
            byte[] pdf = renderer.render(html(
                    "<p>картинка: <img src=\"" + trap.url("/x.png") + "\"/></p>"
                    + "<link rel=\"stylesheet\" href=\"" + trap.url("/x.css") + "\"/>"
                    + "<p style=\"background-image: url('" + trap.url("/bg.png") + "')\">фон</p>"));
            assertThat(KpTestSupport.text(pdf)).contains("картинка", "фон");
            assertThat(trap.hits()).isZero();
        }
    }

    /**
     * Таблицы стилей openhtmltopdf читает только из head — &lt;link&gt; в теле (тест выше) он не запрашивает даже
     * при «разрешить всё», поэтому путь CSS и шрифтов проверяется здесь: link, @import и @font-face в head.
     */
    @Test
    void neverFetchesStylesheetsOrFontsFromHead() throws Exception {
        try (KpTestSupport.TrapServer trap = new KpTestSupport.TrapServer()) {
            byte[] pdf = renderer.render("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                    + "<link rel=\"stylesheet\" href=\"" + trap.url("/x.css") + "\"/>"
                    + "<style>@import url('" + trap.url("/y.css") + "');"
                    + "@font-face { font-family: 'Trap'; src: url('" + trap.url("/f.ttf") + "'); }"
                    + "body { font-family: 'Trap', 'Liberation Serif'; }</style>"
                    + "</head><body><p>текст</p></body></html>");
            assertThat(KpTestSupport.text(pdf)).contains("текст");
            assertThat(trap.hits()).isZero();
        }
    }

    @Test
    void embedsDataUriImage() {
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(KpTestSupport.circlePng());
        byte[] pdf = renderer.render(html("<img src=\"" + png + "\" style=\"width:30mm\"/>"));
        assertThat(KpTestSupport.imageCount(pdf)).isEqualTo(1);
    }

    @Test
    void repeatsTableHeaderOnEveryPage() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 80; i++) rows.append("<tr><td>").append(i).append("</td><td>Позиция ").append(i).append("</td></tr>");
        byte[] pdf = renderer.render(html("<table style=\"width:100%; -fs-table-paginate: paginate\">"
                + "<thead><tr><th>№</th><th>Наименование</th></tr></thead><tbody>" + rows + "</tbody></table>"));
        List<String> pages = KpTestSupport.pageTexts(pdf);
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages).allSatisfy(p -> assertThat(p).contains("Наименование"));
    }

    static String html(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>"
                + "body { font-family: 'Liberation Serif'; font-size: 11pt; }"
                + "thead { display: table-header-group; } td, th { border: 0.5pt solid #000; }"
                + "</style></head><body>" + body + "</body></html>";
    }
}
