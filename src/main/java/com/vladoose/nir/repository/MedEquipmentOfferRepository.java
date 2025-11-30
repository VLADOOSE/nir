package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.MedEquipmentOffer;
import com.vladoose.nir.entity.MedEquipmentOfferId;
public interface MedEquipmentOfferRepository extends JpaRepository<MedEquipmentOffer, MedEquipmentOfferId> {}

