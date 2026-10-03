package com.vladoose.nir.service;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Реквизиты активного рынка для шапок старых PDF/Excel — из «Системы → Реквизиты и печать» (company_profile).
 * Раньше реквизиты были зашиты константами Регион-Мед, и на KZ шапка печатала West-Med с банком Регион-Мед.
 */
@Component
public class CompanyInfoProvider {

    public record Company(String shortName, String fullName, List<String> lines, String directorTitleLine,
                          String directorName, String contacts, String currencyShort, String currencySymbol) {}

    private final CompanyProfileService profiles;

    public CompanyInfoProvider(CompanyProfileService profiles) {
        this.profiles = profiles;
    }

    public Company current() {
        CompanyProfile p = profiles.current();
        String fullName = p.getFullName() == null || p.getFullName().isBlank() ? p.getShortName() : p.getFullName();
        String title = p.getDirectorTitle() == null || p.getDirectorTitle().isBlank()
                ? p.getShortName() : p.getDirectorTitle().trim() + " " + p.getShortName();
        return new Company(p.getShortName(), fullName, CompanyLines.of(p), title, p.getDirectorName(),
                p.getSignoffContacts(), DocFormat.currencyShort(p.getMarket().currencyCode()), p.getMarket().currencySymbol());
    }
}
