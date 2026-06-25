package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InboundEmailRepository extends JpaRepository<InboundEmail, Long> {
    List<InboundEmail> findAllByOrderByReceivedAtDesc();
}
