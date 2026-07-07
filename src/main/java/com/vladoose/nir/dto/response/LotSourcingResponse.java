package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class LotSourcingResponse {

    private List<Entry> distributors;
    private boolean singleLot;
    private DetectedType detectedType;              // только одно-лотовый режим, иначе null
    private List<TypeRef> typeAlternatives;         // альтернативы классификатора (одно-лотовый режим)
    private String sourcingTerm;                    // эффективный термин Tier 2 (префилл поля на фронте)

    @Data
    public static class Entry {
        private DistributorResponse distributor;
        private boolean preselect;
        private boolean relevant;
        private double score;
        private List<BrandHit> matchedBrands;       // обратная совместимость
        private List<Reason> reasons;               // суперсет: типы + бренды (фронт рисует его)
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandHit {
        private String brand;
        private String via;   // PROPOSED_MODEL | REGISTRY | SEARCH_TERM
        private Long lotId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reason {
        private String kind;  // TYPE | BRAND
        private String label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedType {
        private Long id;
        private String name;
        private double confidence;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeRef {
        private Long id;
        private String name;
    }
}
