package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/** Файл письма «прочее» на zakup@ (Excel / Word / PDF) — для «Занести как прайс» во «Входящих» (спека §5.1). */
@Entity
@Table(name = "inbound_email_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundEmailFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inbound_email_id", nullable = false)
    private Long inboundEmailId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] data;

    /** «📒 похоже на прайс»: «прайс», «прейскурант», «price» в имени файла или теме письма. */
    @Column(name = "price_like", nullable = false)
    private boolean priceLike;
}
