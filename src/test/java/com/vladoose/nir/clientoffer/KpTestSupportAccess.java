package com.vladoose.nir.clientoffer;

/** Открытый доступ к картинкам KpTestSupport для тестов других пакетов (печать в документе запроса). */
public final class KpTestSupportAccess {

    private KpTestSupportAccess() {}

    public static byte[] circlePng() {
        return KpTestSupport.circlePng();
    }
}
