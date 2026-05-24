package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProfitabilityReportResponse {

    private Summary summary;
    private List<TopTender> topTenders;
    private List<DistributorMargin> distributorRanking;
    private List<TypeProfit> profitByType;

    @Data
    public static class Summary {
        private int wonApplies;          // число выигранных заявок
        private BigDecimal totalRevenue; // суммарная выручка
        private BigDecimal totalProcurement;
        private BigDecimal totalProfit;
        private BigDecimal marginPercent;
        private BigDecimal avgChequeProfit;  // средняя прибыль на заявку
    }

    @Data
    public static class TopTender {
        private Long applyId;
        private String tenderNumber;
        private String facilityName;
        private BigDecimal revenue;
        private BigDecimal profit;
        private BigDecimal marginPercent;
    }

    @Data
    public static class DistributorMargin {
        private Long distributorId;
        private String name;
        private int dealsCount;          // сколько позиций мы у них взяли в WON-заявках
        private BigDecimal totalProfit;
        private BigDecimal avgMarginPercent;
    }

    @Data
    public static class TypeProfit {
        private String typeName;
        private int positionsCount;
        private BigDecimal totalProfit;
        private BigDecimal avgMarginPercent;
    }
}
