package com.vladoose.nir.service.offer;

import java.util.List;

/** items — в том же порядке и той же длины, что offer.getItems(). */
public record OfferCalculation(List<ItemCalc> items, OfferTotals totals) {}
