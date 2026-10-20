package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;

import java.util.List;

/** Каталог westmed.kz (спека 2026-10-19-product-watch §6). Публичную карточку /api/v1/products/{slug} не зовём — просмотры. */
public interface WestmedCatalogClient {

    /** Задана учётка админ-API сайта (leads.westmed.username / password). */
    boolean adminConfigured();

    /** Активные товары с карточками (описание, характеристики, РУ): список по 100 + карточка каждого. */
    List<WestmedCatalogItem> adminCatalog();

    /** Публичный список (название, бренд, раздел, краткое описание) — для разработки без пароля сайта. */
    List<WestmedCatalogItem> publicCatalog();
}
