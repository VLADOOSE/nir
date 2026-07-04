package com.vladoose.nir.exception;

/** 502: внешний сервис (goszakup и т.п.) недоступен или ответил ошибкой. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message, Throwable cause) { super(message, cause); }
}
