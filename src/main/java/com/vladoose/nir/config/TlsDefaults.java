package com.vladoose.nir.config;

/**
 * Настройки TLS, которые нужны JVM ДО первого HTTPS-соединения.
 *
 * <p>Сайты площадок бывают настроены с ошибкой: отдают свой сертификат без промежуточного.
 * Так с 2026-09-28 работает fms.ecc.kz (СК-Фармация, сертификат GoGetSSL) — браузер докачивает
 * промежуточный по адресу из самого сертификата (AIA, «CA Issuers»), а Java по умолчанию нет и
 * обрывает соединение «PKIX path building failed»: импорт СК-Фармации и кнопка «ТЗ» стояли.
 *
 * <p>Флаг JDK {@code com.sun.security.enableAIAcaIssuers} включает ту же докачку. Доверия он не
 * добавляет: достроенная цепочка всё равно должна сойтись к корню из стандартного хранилища JVM.
 * Флаг читается ОДИН раз (static final в {@code sun.security.provider.certpath.Builder}) — поэтому
 * вызывается из статического блока класса приложения, раньше любого соединения.
 * Выключить без правки кода: {@code -Dcom.sun.security.enableAIAcaIssuers=false} в JAVA_OPTS.
 */
public final class TlsDefaults {

    private static final String AIA_PROPERTY = "com.sun.security.enableAIAcaIssuers";

    private TlsDefaults() {
    }

    /** Включает докачку промежуточных сертификатов, если её не задали явно в командной строке. */
    public static void enableAiaFetching() {
        if (System.getProperty(AIA_PROPERTY) == null) {
            System.setProperty(AIA_PROPERTY, "true");
        }
    }
}
