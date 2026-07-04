package com.vladoose.nir.exception;

/** 422: вход получен, но обработать содержимое невозможно (напр., нечитаемый PDF). */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) { super(message); }
}
