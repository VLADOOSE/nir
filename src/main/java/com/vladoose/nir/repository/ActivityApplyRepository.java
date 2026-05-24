package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ActivityApply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityApplyRepository extends JpaRepository<ActivityApply, Long> {

    List<ActivityApply> findByTenderId(Long tenderId);

    boolean existsByTenderId(Long tenderId);

    List<ActivityApply> findByStatus(String status);

    boolean existsByStatus(String status);
}
