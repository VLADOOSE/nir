package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lead_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeadItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    /** Порядковый номер строки (не «position»: это функция HQL, разбор @OrderBy по ней ненадёжен). */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 500)
    private String name;

    private String brand;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "product_url", length = 500)
    private String productUrl;
}
