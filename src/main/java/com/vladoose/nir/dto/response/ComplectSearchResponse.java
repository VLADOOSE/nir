package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

/** Результат поиска по комплектности аппаратов: термин + аппараты с подходящими компонентами. */
@Data
public class ComplectSearchResponse {
    private String term;
    private List<ApparatusMatch> apparatuses;

    @Data
    public static class ApparatusMatch {
        private String regNumber;
        private String name;
        private String producer;
        private String country;
        private List<ComponentMatch> components;
    }

    @Data
    public static class ComponentMatch {
        private Integer partNumber;
        private String productName;
        private String component;
        private String producer;
        private String country;
        private Double score;
    }
}
