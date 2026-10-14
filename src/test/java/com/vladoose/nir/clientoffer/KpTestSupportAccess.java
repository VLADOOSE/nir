package com.vladoose.nir.clientoffer;

/** Открытый доступ к помощникам KpTestSupport для тестов других пакетов (печать и PDF документа запроса). */
public final class KpTestSupportAccess {

    private KpTestSupportAccess() {}

    public static byte[] circlePng() {
        return KpTestSupport.circlePng();
    }

    /** Текст PDF одной строкой: пробельные — один пробел, неразрывные — обычные. */
    public static String pdfText(byte[] pdf) {
        return KpTestSupport.text(pdf);
    }

    /** Сколько растровых картинок нарисовано на страницах PDF. */
    public static int pdfImageCount(byte[] pdf) {
        return KpTestSupport.imageCount(pdf);
    }
}
