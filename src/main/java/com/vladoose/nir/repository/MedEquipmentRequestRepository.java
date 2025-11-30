package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.MedEquipmentRequest;
import com.vladoose.nir.entity.MedEquipmentRequestId;
public interface MedEquipmentRequestRepository extends JpaRepository<MedEquipmentRequest, MedEquipmentRequestId> {}

