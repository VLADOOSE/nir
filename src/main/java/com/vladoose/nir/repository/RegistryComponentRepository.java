package com.vladoose.nir.repository;

import com.vladoose.nir.entity.RegistryComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistryComponentRepository extends JpaRepository<RegistryComponent, Long> {

    List<RegistryComponent> findByRegNumberOrderByPartNumber(String regNumber);

    Optional<RegistryComponent> findByRegNumberAndPartNumber(String regNumber, Integer partNumber);

    void deleteByRegNumber(String regNumber);
}
