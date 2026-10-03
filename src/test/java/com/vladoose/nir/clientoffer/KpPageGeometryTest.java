package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.service.document.KpHtmlRenderer;
import com.vladoose.nir.service.document.KpPageGeometry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Одна геометрия листа на PDF, подбор колонок и Word (KpPageGeometry). Поля листа PDF приходят из неё (@page), а поля
 * ячеек и размеры подписи шаблон держит у себя в CSS — здесь они сверяются с геометрией: иначе доли колонок, посчитанные
 * под одни поля, печатались бы с другими, а подпись в Word разошлась бы с PDF.
 */
class KpPageGeometryTest {

    @Test
    void textWidthIsTheSheetWithoutItsSideMargins() {
        assertThat(KpPageGeometry.textWidthMm(false)).isEqualTo(210.0 - 20 - 12);
        assertThat(KpPageGeometry.textWidthMm(true)).isEqualTo(297.0 - 15 - 15);
    }

    @Test
    void pdfPageRuleIsTheGeometry() {
        for (boolean landscape : new boolean[] {false, true}) {
            ClientOffer o = KpFixtures.offer2409();
            o.setLandscape(landscape);
            String html = new KpHtmlRenderer(KpFixtures.templateEngine()).render(KpFixtures.document(o, KpFixtures.profileKz()));
            KpPageGeometry.Margins m = KpPageGeometry.margins(landscape);
            assertThat(html).as(landscape ? "альбомная" : "книжная").contains("@page { size: A4" + (landscape ? " landscape" : "")
                    + "; margin: " + mm(m.top()) + " " + mm(m.right()) + " " + mm(m.bottom()) + " " + mm(m.left()) + "; }");
        }
    }

    @Test
    void templateCellPaddingIsTheGeometry() throws IOException {
        String css = template();
        String padding = mm(KpPageGeometry.CELL_PADDING_V_MM) + " " + mm(KpPageGeometry.CELL_PADDING_H_MM);
        assertThat(declaration(css, ".items th, .items td", "padding")).isEqualTo(padding);
        assertThat(declaration(css, ".terms-table td", "padding")).isEqualTo(padding);
    }

    /** Размеры подписи в шаблоне — те же, по которым строит подпись Word (KpDocxRenderer). */
    @Test
    void templateSignoffSizesAreTheGeometry() throws IOException {
        String css = template();
        assertThat(declaration(css, ".sign-line", "width")).isEqualTo(mm(KpPageGeometry.SIGN_LINE_WIDTH_MM));
        assertThat(declaration(css, ".sign-line", "height")).isEqualTo(mm(KpPageGeometry.SIGN_HEIGHT_MM));
        assertThat(declaration(css, ".sign-box", "height")).isEqualTo(mm(KpPageGeometry.SIGN_HEIGHT_MM));
        assertThat(declaration(css, ".sign-pic img", "max-height")).isEqualTo(mm(KpPageGeometry.SIGN_HEIGHT_MM));
        assertThat(declaration(css, ".sign-pic img", "max-width")).isEqualTo(mm(KpPageGeometry.SIGNATURE_MAX_WIDTH_MM));
        assertThat(declaration(css, ".sign-table .sign-name", "padding-left")).isEqualTo(mm(KpPageGeometry.SIGN_NAME_GAP_MM));
    }

    static String template() throws IOException {
        try (InputStream in = KpPageGeometryTest.class.getResourceAsStream("/templates/kp/offer.html")) {
            assertThat(in).as("шаблон КП в classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Значение свойства в CSS-правиле шаблона с ровно таким селектором. */
    static String declaration(String css, String selector, String property) {
        Matcher rule = Pattern.compile("(?:^|[}\\s])" + Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertThat(rule.find()).as("правило «%s» в offer.html", selector).isTrue();
        Matcher value = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(property) + "\\s*:\\s*([^;]+)").matcher(rule.group(1));
        assertThat(value.find()).as("«%s» в правиле «%s»", property, selector).isTrue();
        return value.group(1).trim();
    }

    static String mm(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + "mm";
    }
}
