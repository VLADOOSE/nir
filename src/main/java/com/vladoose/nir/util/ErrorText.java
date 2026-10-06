package com.vladoose.nir.util;

import java.util.regex.Pattern;

/** Текст исключения для человека — в итог прогона, сообщение API, лог. */
public final class ErrorText {

    /** «sun.security.provider.certpath.SunCertPathBuilderException: » внутри текста — шум для оператора. */
    private static final Pattern JAVA_CLASS_PREFIX =
            Pattern.compile("\\b(?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:Exception|Error): ");

    private ErrorText() {
    }

    /**
     * Сообщение исключения без вложенных имён Java-классов, а без сообщения — имя класса: HttpClient JDK 17
     * при отказе в соединении бросает {@code ConnectException} без текста, и в логе прода стояло
     * «goszakup API недоступно: null».
     */
    public static String of(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return JAVA_CLASS_PREFIX.matcher(message).replaceAll("");
    }
}
