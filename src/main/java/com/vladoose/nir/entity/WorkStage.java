package com.vladoose.nir.entity;

/** Наша стадия работы по тендеру (воронка) — производная, не хранится. */
public enum WorkStage {
    NOT_STARTED,      // нет КП по тендеру
    REQUESTED,        // есть КП, но нет ни одной responsePrice
    PRICED,           // есть ≥1 responsePrice
    WINNER_SELECTED   // есть ActivityApply с ≥1 позицией
}
