package com.vladoose.nir.repository;

import com.vladoose.nir.entity.GoszakupSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GoszakupSubjectRepository extends JpaRepository<GoszakupSubject, String> {
    List<GoszakupSubject> findByBinIn(Collection<String> bins);
}
