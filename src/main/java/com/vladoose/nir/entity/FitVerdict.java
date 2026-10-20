package com.vladoose.nir.entity;

/** Вердикт лота × товара (спека §6.3); rank — для выбора лучшего товара. */
public enum FitVerdict {
    FITS(5), FITS_NO_RU(4), PARTIAL(3), NOT_FIT(2), NO_REQUIREMENTS(1);

    private final int rank;
    FitVerdict(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
