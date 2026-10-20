package com.vladoose.nir.service.productwatch;

import org.springframework.stereotype.Component;

/**
 * Снимок групп и товаров для правил «нашей продукции» (спека 2026-10-19-product-watch §5.6). Заготовка задачи 2:
 * загрузка каталога уже зовёт {@link #refresh()}; сам снимок — задача 3.
 */
@Component
public class ProductCatalog {

    /** Перечитать группы и товары (после загрузки каталога и сохранения групп). */
    public void refresh() {
    }

    /** Сбросить снимок — следующее чтение соберёт его заново (тесты, откатившие свою транзакцию). */
    public void invalidate() {
    }
}
