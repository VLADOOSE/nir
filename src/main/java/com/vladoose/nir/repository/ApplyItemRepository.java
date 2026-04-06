package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ApplyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplyItemRepository extends JpaRepository<ApplyItem, Long> {

    List<ApplyItem> findByApplyId(Long applyId);
}
