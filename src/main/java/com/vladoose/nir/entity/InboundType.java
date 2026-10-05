package com.vladoose.nir.entity;

public enum InboundType {
    SUPPLIER_RESPONSE, CLIENT_REQUEST, UNMATCHED,
    /** «Письмо не доставлено» — возврат почтового сервера; статус запроса КП не меняется. */
    BOUNCE,
    /** Автоответ («в отпуске», Auto-Submitted); статус запроса КП не меняется. */
    AUTO_REPLY
}
