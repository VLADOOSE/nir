package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class BulkPricePreviewResponse {
    private List<Group> groups;
    private List<TenderLotShortResponse> lotsWithoutMatch;
    private List<TenderLotShortResponse> lotsWithoutDistributor;

    @Data
    public static class Group {
        private DistributorResponse distributor;
        private List<Item> items;
    }

    @Data
    public static class Item {
        private TenderLotShortResponse lot;
        private MedEquipmentResponse equipment;
    }
}
