package com.vladoose.nir.service.productwatch;

/** Откуда правилам брать снимок: в работе — {@link ProductCatalog}, в тестах — лямбда со своим снимком. */
@FunctionalInterface
public interface CatalogSource {
    CatalogSnapshot get();
}
