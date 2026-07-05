package com.vladoose.nir.exception;

/** 502: внешний сервис (goszakup, НЦЭЛС и т.п.) недоступен или ответил ошибкой. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message) { super(message); }
    public UpstreamException(String message, Throwable cause) { super(message, cause); }
}
