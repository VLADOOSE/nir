package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProductDigest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductDigestRepository extends JpaRepository<ProductDigest, Long> {
    boolean existsByMessageId(Long messageId);
}
