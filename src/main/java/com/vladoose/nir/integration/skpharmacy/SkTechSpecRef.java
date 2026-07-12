package com.vladoose.nir.integration.skpharmacy;

/** Ссылка на файл техспецификации лота в модалке fms.ecc.kz: № лота площадки + прямой URL PDF. */
public record SkTechSpecRef(String lotCode, String pdfUrl, String fileName) {}
