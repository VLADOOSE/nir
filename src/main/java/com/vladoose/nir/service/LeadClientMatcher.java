package com.vladoose.nir.service;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.stereotype.Service;

import java.util.List;

/** Клиент обращения: по телефону (последние 10 цифр), иначе по email. Берём только ОДНОЗНАЧНОЕ совпадение. */
@Service
public class LeadClientMatcher {

    private final FacilityRepository facilityRepository;

    public LeadClientMatcher(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    public Facility match(Market market, String phoneNorm, String email) {
        String last10 = PhoneNormalizer.last10(phoneNorm);
        if (last10 != null) {
            List<Facility> byPhone = facilityRepository.findByMarketAndPhoneLast10(market.name(), last10);
            if (byPhone.size() == 1) return byPhone.get(0);
        }
        if (email != null && !email.isBlank()) {
            List<Facility> byEmail = facilityRepository.findByMarketAndEmailIgnoreCase(market, email.trim());
            if (byEmail.size() == 1) return byEmail.get(0);
        }
        return null;
    }
}
