package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.Company;
public interface CompanyRepository extends JpaRepository<Company, Long> {}