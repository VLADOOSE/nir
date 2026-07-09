package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferComparisonResponse {
    private List<Lot> lots;
    private List<Supplier> suppliers;
    private List<Cell> cells;
    private Map<Long, Long> bestByLot;                 // lotId → priceRequestId (мин. цена)
    private Map<Long, BigDecimal> totalsBySupplier;    // priceRequestId → Σ(price×qty)

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Lot { private Long lotId; private Integer lotNumber; private String lotName; private Integer quantity; }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Supplier { private Long priceRequestId; private String distributorName; private String status; }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Cell { private Long lotId; private Long priceRequestId; private BigDecimal responsePrice; private Integer quantity; }
}
