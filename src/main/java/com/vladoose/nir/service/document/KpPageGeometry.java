package com.vladoose.nir.service.document;

/**
 * Геометрия листа КП — одна на оба документа (спека §6.4): поля @page шаблона PDF (KpHtmlRenderer.pageCss), ширина
 * набора и поля ячеек в подборе колонок (KpDocumentBuilder), поля страницы и ячеек Word (KpDocxRenderer). Доли колонок
 * KpDocument значат одно и то же в PDF и в Word, только пока у обоих одни поля листа и ячеек. Поля ячеек шаблон
 * offer.html держит у себя в CSS — их с этими сверяет KpPageGeometryTest. Всё — в мм.
 */
public final class KpPageGeometry {

    /** Лист A4, мм. */
    public static final double A4_SHORT_MM = 210, A4_LONG_MM = 297;

    /** Поля листа, мм (как поля страницы в Word). */
    public record Margins(double top, double right, double bottom, double left) {}

    /** Книжный лист: слева 20, справа 12, сверху и снизу 15 мм. */
    public static final Margins PORTRAIT = new Margins(15, 12, 15, 20);
    /** Альбомный лист: слева и справа 15, сверху и снизу 12 мм. */
    public static final Margins LANDSCAPE = new Margins(12, 15, 12, 15);

    /**
     * Поле ячейки таблиц позиций и условий, мм (offer.html: padding: 1.2mm 1.5mm): по горизонтали — с каждой стороны,
     * по вертикали — сверху и снизу.
     */
    public static final double CELL_PADDING_H_MM = 1.5, CELL_PADDING_V_MM = 1.2;

    private KpPageGeometry() {}

    public static Margins margins(boolean landscape) {
        return landscape ? LANDSCAPE : PORTRAIT;
    }

    public static double pageWidthMm(boolean landscape) {
        return landscape ? A4_LONG_MM : A4_SHORT_MM;
    }

    public static double pageHeightMm(boolean landscape) {
        return landscape ? A4_SHORT_MM : A4_LONG_MM;
    }

    /** Ширина набора — лист без полей слева и справа: 178 мм книжный, 267 мм альбомный. */
    public static double textWidthMm(boolean landscape) {
        Margins m = margins(landscape);
        return pageWidthMm(landscape) - m.left() - m.right();
    }
}
