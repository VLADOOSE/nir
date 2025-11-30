package com.vladoose.nir.dto;

import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class TenderSearchResult {
    private Long tndrId;
    private String organization;
    private Double cost;
    private String stage;
    private Integer lotsCount;
    // optional lot info
    private List<Object> lots;
}
