package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.TenderStep;
import com.vladoose.nir.entity.TenderStepId;
public interface TenderStepRepository extends JpaRepository<TenderStep, TenderStepId> {}