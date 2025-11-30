package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.Tender;
public interface TenderRepository extends JpaRepository<Tender, Long> {}
