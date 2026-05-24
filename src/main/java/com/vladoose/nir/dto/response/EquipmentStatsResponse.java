package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class EquipmentStatsResponse {
    private List<DistributorResponse> potentialDistributors;
    private Summary summary;
    private List<DistributorRating> ranking;
    private List<HistoryEntry> history;

    @Data
    public static class Summary {
        private int requestsCount;
        private int distinctDistributors;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private BigDecimal avgPrice;
    }

    @Data
    public static class DistributorRating {
        private DistributorResponse distributor;
        private int responsesCount;
        private BigDecimal avgPrice;
    }

    @Data
    public static class HistoryEntry {
        private OffsetDateTime date;
        private DistributorResponse distributor;
        private String tenderNumber;
        private Integer requestedQuantity;
        private BigDecimal responsePrice;
        private String status;
    }
}
