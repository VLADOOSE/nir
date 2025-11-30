package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {}
