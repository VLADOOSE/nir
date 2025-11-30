package com.vladoose.nir.dto;

import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class ActivityApplyRequest {
    private Long tenderId;
    private Long facilityId;
    private List<ActivityItem> items;

    @Data public static class ActivityItem {
        private Long medEquipId;
        private Integer qty;
    }
}
