package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class LotSourcingResponse {

    private List<Entry> distributors;

    @Data
    public static class Entry {
        private DistributorResponse distributor;
        private boolean preselect;
        private List<BrandHit> matchedBrands;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandHit {
        private String brand;
        private String via;  // PROPOSED_MODEL | REGISTRY
        private Long lotId;
    }
}
