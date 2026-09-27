package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PollResultResponse {
    private boolean enabled;
    private int fetched;
    private int supplierResponses;
    private int clientRequests;
    private int unmatched;
    /** Письма-уведомления westmed.kz о заявках с сайта: сами заявки приходят через API сайта (обращения). */
    private int skippedSiteNotifications;
    private String message;
}
