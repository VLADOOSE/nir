package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ActivityApplyResponse {
    private Long id;
    private TenderShortResponse tender;
    private String status;
    private OffsetDateTime createdAt;
    /**
     * Always empty in responses — the frontend loads items separately
     * via GET /applies/{id}/items. Kept here for shape compatibility.
     */
    private List<Object> items = new ArrayList<>();
}
