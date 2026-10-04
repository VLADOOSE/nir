package com.vladoose.nir.service.document;

/**
 * Геометрия листа КП — одна на оба документа (спека §6.4): поля @page шаблона PDF (KpHtmlRenderer.pageCss), ширина
 * набора и поля ячеек в подборе колонок (KpDocumentBuilder), поля страницы и ячеек Word (KpDocxRenderer); там же —
 * подпись и место печати. Доли колонок KpDocument значат одно и то же в PDF и в Word, только пока у обоих одни поля листа
 * и ячеек. Поля ячеек и размеры подписи шаблон offer.html держит у себя в CSS — их с этими сверяет KpPageGeometryTest.
 * Всё — в мм.
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

    /**
     * На сколько таблицы условий и позиций в PDF отодвинуты от краёв набора, мм, с каждой стороны (offer.html: .ruled):
     * не меньше точки предпросмотра 110 dpi — предпросмотр режет лист по целым точкам, и крайняя рамка вровень с полем
     * теряла часть точки. Доли колонок — от ширины таблицы; полмиллиметра покрывает запас в нужде колонок
     * (KpDocumentBuilder). Word ставит таблицы вровень с полем: он рамок по полю не режет.
     */
    public static final double TABLE_INSET_MM = 0.25;

    /**
     * Подпись директора (offer.html: .sign-line, .sign-box, .sign-pic img, .sign-table .sign-name): линия подписи — 45 мм;
     * строка подписи — 15 мм высотой, картинка подписи — не выше её и не шире 44 мм; фамилия — в 3 мм после линии.
     */
    public static final double SIGN_LINE_WIDTH_MM = 45, SIGN_HEIGHT_MM = 15, SIGNATURE_MAX_WIDTH_MM = 44, SIGN_NAME_GAP_MM = 3;

    /**
     * Где печать, мм (спека §6.3: «ложится центром у левого края линии подписи»): центр печати — в STAMP_CENTER_X_MM
     * правее начала линии подписи и в STAMP_CENTER_Y_MM ниже её (минус — выше: центр на строке должности). Длина
     * должности не важна — печать держится за линию, а не за блок «С уважением…». У подписи от компании линии нет —
     * её началом служит конец названия компании. PDF (KpHtmlRenderer) и Word (KpDocxRenderer) ставят печать по этим числам.
     */
    public static final double STAMP_CENTER_X_MM = 0, STAMP_CENTER_Y_MM = -4;

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
