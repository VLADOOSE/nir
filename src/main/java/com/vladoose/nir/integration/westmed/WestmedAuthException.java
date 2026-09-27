package com.vladoose.nir.integration.westmed;

/** Сайт не пускает учётку АИС (нет данных, неверный пароль, 429, отказ после повторного входа). */
public class WestmedAuthException extends RuntimeException {

    public WestmedAuthException(String message) {
        super(message);
    }
}
