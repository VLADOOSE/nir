package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MedEquipment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface MedEquipmentRepository extends JpaRepository<MedEquipment, Long> {

    List<MedEquipment> findByEquipmentTypeId(Long equipmentTypeId);

    /** Позиция каталога, уже привязанная к РУ (дедуп при «Взять из реестра»); рыночный фильтр — аспектом. */
    Optional<MedEquipment> findFirstByRegistrationRegNumber(String regNumber);

    /** Дедуп позиции по РУ + имени (разные компоненты одного аппарата не сливаются). */
    Optional<MedEquipment> findFirstByRegistrationRegNumberAndNameIgnoreCase(String regNumber, String name);

    /** Позиция каталога, заведённая из нашего товара сайта (V40: одна на товар). */
    Optional<MedEquipment> findFirstBySiteProductId(Long siteProductId);

    /** Поиск по названию и производителю (спека 2026-10-13 §2.4); рынок — фильтром аспекта, РУ — сразу (без N+1). */
    @Query("SELECT e FROM MedEquipment e LEFT JOIN FETCH e.registration " +
           "WHERE lower(e.name) LIKE :p ESCAPE '\\' OR lower(e.manufact) LIKE :p ESCAPE '\\' ORDER BY e.name, e.id")
    List<MedEquipment> search(@Param("p") String pattern, Pageable page);

    @Query("SELECT e FROM MedEquipment e WHERE " +
           "(:equipTypeId IS NULL OR e.equipmentType.id = :equipTypeId) AND " +
           "(:maxLength IS NULL OR e.lengthMm <= :maxLength) AND " +
           "(:maxWidth IS NULL OR e.widthMm <= :maxWidth) AND " +
           "(:maxHeight IS NULL OR e.heightMm <= :maxHeight) AND " +
           "(:maxWeight IS NULL OR e.weightKg <= :maxWeight) " +
           "ORDER BY e.name ASC")
    List<MedEquipment> findMatchingEquipment(
            @Param("equipTypeId") Long equipTypeId,
            @Param("maxLength") Integer maxLength,
            @Param("maxWidth") Integer maxWidth,
            @Param("maxHeight") Integer maxHeight,
            @Param("maxWeight") BigDecimal maxWeight);
}
