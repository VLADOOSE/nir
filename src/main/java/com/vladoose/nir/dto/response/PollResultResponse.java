package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PollResultResponse {
    private boolean enabled;
    private int fetched;
    private int supplierResponses;
    private int clientRequests;
    private int unmatched;
    private String message;
}
