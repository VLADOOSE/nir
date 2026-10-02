package com.vladoose.nir.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Условие КП «название — значение»; пустое название — в списке печатается только значение. JSON. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferTerm {
    private String label;
    private String value;
}
