package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.util.BrandMatch;

import java.util.List;

/** Кому бот готовит письмо по бренду модели (спека 2026-10-21-tender-telegram-bot §2 п. 5): завод с почтой, иначе «Доверенные». */
public final class LetterRecipients {

    private LetterRecipients() {}

    public static List<Distributor> pick(List<Distributor> all, String brand) {
        if (brand == null || brand.isBlank() || "не указан".equalsIgnoreCase(brand.strip())) return List.of();
        List<Distributor> withBrand = all.stream()
                .filter(d -> d.getEmail() != null && !d.getEmail().isBlank())
                .filter(d -> BrandMatch.firstCarried(d.getBrands(), brand) != null).toList();
        List<Distributor> makers = withBrand.stream().filter(Distributor::isManufacturer).toList();
        return makers.isEmpty() ? withBrand.stream().filter(Distributor::isTrusted).toList() : makers;
    }
}
