package com.vladoose.nir.entity;

/** Событие шлюза WhatsApp в очереди: ждёт разбора / разобрано / пропущено как «ядовитое» (спека whatsapp-waha §5.2). */
public enum WhatsappInboxStatus { PENDING, DONE, DROPPED }
