package com.vladoose.nir.entity;

/** Событие итогов (спека §7, §12). */
public enum ResultEventKind {
    DONE, NO_BIDS, ONE_BID, FAILED_OTHER, OI_DONE, OI_FAILED, CANCELLED, CONTRACT, REPEAT, OUR_BID;

    /** «Звонить заказчику» и наша заявка — сразу; остальное — в сводку дня. */
    public boolean urgent() {
        return this == NO_BIDS || this == FAILED_OTHER || this == OI_FAILED || this == OUR_BID;
    }
}
