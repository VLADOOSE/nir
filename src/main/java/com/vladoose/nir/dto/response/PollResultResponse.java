package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PollResultResponse {
    private boolean enabled;
    /**
     * Проход дошёл до конца: ящик открылся, и проход не остановился на сбое (обрыв связи с почтой, база недоступна).
     * Битое письмо и сбой отправки в Telegram — не провал проверки: о них говорят счётчики и текст message.
     * false — и когда приём выключен.
     */
    private boolean ok;
    /**
     * Ручную проверку не дождались (ответ — через 90 с): проход ещё идёт и закончится сам, его итог появится
     * во «Входящих». ok при этом false — итога пока нет.
     */
    private boolean pending;
    /** Записано новых писем (без дублей и уведомлений сайта; с неразобранными). */
    private int fetched;
    private int supplierResponses;
    private int clientRequests;
    private int unmatched;
    /** «Письмо не доставлено» — возвраты почтовых серверов. */
    private int bounces;
    private int autoReplies;
    /** Письма, которые не разобрались или не записались (записаны короткой строкой). */
    private int broken;
    /** Письма-уведомления westmed.kz о заявках с сайта: сами заявки приходят через API сайта (обращения). */
    private int skippedSiteNotifications;
    private int telegramSent;
    private long telegramPending;
    private String message;
}
