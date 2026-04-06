package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Tender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderRepository extends JpaRepository<Tender, Long> {
}
