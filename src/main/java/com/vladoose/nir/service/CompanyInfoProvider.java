package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.stereotype.Component;

/** Отдаёт реквизиты компании активного рынка для шапок PDF/Excel. */
@Component
public class CompanyInfoProvider {

    public record Company(String shortName, String fullName, String addressLine1, String addressLine2) {}

    public Company current() {
        if (MarketContext.get() == Market.KZ) {
            return new Company(
                    "ТОО «West-Med»",
                    "Товарищество с ограниченной ответственностью «West-Med»",
                    "Республика Казахстан, г. Алматы,",
                    "ул. (реквизиты уточняются)");
        }
        return new Company(
                CompanyInfo.SHORT_NAME, CompanyInfo.FULL_NAME,
                CompanyInfo.ADDRESS_LINE_1, CompanyInfo.ADDRESS_LINE_2);
    }
}
