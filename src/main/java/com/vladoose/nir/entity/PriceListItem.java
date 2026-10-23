package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Строка прайса (спека §5.3): цены по колонкам прайса (null — ячейка пуста), закупка после скидки в валюте прайса,
 * РУ из прайса, связь с нашим товаром или позицией каталога, ключ модели («дкин12»). Не рыночная — через прайс.
 */
@Entity
@Table(name = "price_list_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false)
    private PriceList priceList;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(length = 255)
    private String section;

    @Column(nullable = false, length = 1000)
    private String name;

    @Column(length = 100)
    private String model;

    @Column(length = 100)
    private String article;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<BigDecimal> prices = new ArrayList<>();

    @Column(name = "our_price", precision = 15, scale = 2)
    private BigDecimal ourPrice;

    @Column(name = "reg_text", length = 500)
    private String regText;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Column(name = "no_reg", nullable = false)
    private boolean noReg;

    @Column(name = "site_product_id")
    private Long siteProductId;

    @Column(name = "med_equipment_id")
    private Long medEquipmentId;

    @Column(name = "model_key", length = 100)
    private String modelKey;

    @Column(name = "file_row")
    private Integer fileRow;

    @Column(length = 500)
    private String note;
}
