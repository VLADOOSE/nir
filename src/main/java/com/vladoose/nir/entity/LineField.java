package com.vladoose.nir.entity;

public enum LineField {
    NAME, MANUFACT, QUANTITY, IGNORE,
    /** Роли колонок КП поставщика (спека 2026-10-15 §3.2); экраны позиций клиента их не показывают (решение плана 12). */
    NUMBER, ARTICLE, UNIT, PRICE, SUM, VAT, CURRENCY, NOTE
}
