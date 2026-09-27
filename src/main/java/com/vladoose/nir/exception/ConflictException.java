package com.vladoose.nir.exception;

/** 409: действие не подходит к текущему состоянию записи (например, допустить уже решённый запрос). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
