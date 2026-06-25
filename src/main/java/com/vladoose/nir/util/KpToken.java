package com.vladoose.nir.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Токен сопоставления КП в теме письма: [КП-<id>]. Единый формат для отправки и приёма. */
public final class KpToken {

    private static final Pattern PATTERN = Pattern.compile("\\[КП-(\\d+)\\]");

    private KpToken() {}

    public static String subjectToken(Long priceRequestId) {
        return "[КП-" + priceRequestId + "]";
    }

    public static Optional<Long> parse(String subject) {
        if (subject == null) return Optional.empty();
        Matcher m = PATTERN.matcher(subject);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }
}
