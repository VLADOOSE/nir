package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProductRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;

public interface ProductRegistrationRepository extends JpaRepository<ProductRegistration, String> {
    @Query("select max(r.checkedAt) from ProductRegistration r")
    OffsetDateTime lastChecked();
}
