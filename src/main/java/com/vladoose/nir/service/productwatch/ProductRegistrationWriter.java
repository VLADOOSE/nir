package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductRegistration;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.RegistrationComponent;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Запись проверки РУ — своей транзакцией на номер (сеть — снаружи, в {@link ProductRegistrationSync}). */
@Component
public class ProductRegistrationWriter {

    private final ProductRegistrationRepository repo;

    public ProductRegistrationWriter(ProductRegistrationRepository repo) { this.repo = repo; }

    @Transactional
    public void save(String number, NddaRegistration r, NddaDetailDto d, List<NddaComplectItemDto> complect) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number).build());
        p.setStatus(r.active() ? RegStatus.ACTIVE : RegStatus.EXPIRED);
        p.setNddaId(r.id());
        p.setName(r.name());
        p.setProducer(trunc(r.producer(), 500));
        p.setCountry(trunc(r.country(), 200));
        p.setRegDate(r.regDate());
        p.setExpirationDate(r.expirationDate());
        p.setUnlimited(r.unlimited());
        p.setPurpose(d == null ? null : d.getPurpose());
        p.setUseArea(d == null ? null : d.getUseArea());
        p.setTechChars(d == null ? null : d.getShortTechnicalCharacteristicsRu());
        p.setRiskClass(d == null ? null : trunc(d.getDegreeRiskName(), 200));
        p.setComplect(complect == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(complect.stream()
                .map(c -> new RegistrationComponent(c.getComponent() != null ? c.getComponent() : c.getProductName(), c.getProducerName(),
                        c.getCountryName())).toList()));
        p.setCheckedAt(OffsetDateTime.now());
        p.setError(null);
        repo.save(p);
    }

    @Transactional
    public void notFound(String number) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number).build());
        p.setStatus(RegStatus.NOT_FOUND);
        p.setCheckedAt(OffsetDateTime.now());
        p.setError(null);
        repo.save(p);
    }

    /** Сбой номера: прежние данные остаются; строки не было — UNKNOWN. */
    @Transactional
    public void error(String number, String error) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number)
                .status(RegStatus.UNKNOWN).build());
        p.setError(trunc(error, 300));
        p.setCheckedAt(OffsetDateTime.now());
        repo.save(p);
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
