package com.vladoose.nir.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class EquipmentMatchResponse {

    private Long lotId;
    private BigDecimal lotMaxCost;
    private boolean hasHistory;
    private String preset;
    private WeightsUsed weightsUsed;
    private List<Candidate> candidates;

    @Getter @Setter @NoArgsConstructor
    public static class WeightsUsed {
        private double price;
        private double margin;
        private double track;
        private double dim;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Candidate {
        private Long equipmentId;
        private String name;
        private String manufact;
        private String equipType;
        private Integer lengthMm;
        private Integer widthMm;
        private Integer heightMm;
        private BigDecimal weightKg;
        private String spec;
        private double score;
        private int rank;
        private boolean recommended;
        private Breakdown breakdown;
        private BestDistributor bestDistributor;
        private BigDecimal estimatedPrice;
        private BigDecimal estimatedMargin;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Breakdown {
        private SubScore price;
        private SubScore margin;
        private SubScore track;
        private SubScore dim;
    }

    @Getter @Setter @NoArgsConstructor
    public static class SubScore {
        private double value;
        private boolean noData;
        private String raw;

        public SubScore(double value, boolean noData, String raw) {
            this.value = value;
            this.noData = noData;
            this.raw = raw;
        }
    }

    @Getter @Setter @NoArgsConstructor
    public static class BestDistributor {
        private Long distributorId;
        private String name;
        private int dealsCount;
        private BigDecimal avgMarginPercent;
    }
}
