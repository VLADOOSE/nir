package com.vladoose.nir.repository;

import com.vladoose.nir.entity.HeaderSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HeaderSynonymRepository extends JpaRepository<HeaderSynonym, Long> {
    Optional<HeaderSynonym> findByHeaderNorm(String headerNorm);
}
