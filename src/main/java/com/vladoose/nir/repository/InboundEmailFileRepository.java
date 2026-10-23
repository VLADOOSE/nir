package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmailFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InboundEmailFileRepository extends JpaRepository<InboundEmailFile, Long> {
    /** Без байтов: [id, inboundEmailId, fileName, priceLike]. */
    @Query("SELECT f.id, f.inboundEmailId, f.fileName, f.priceLike FROM InboundEmailFile f WHERE f.inboundEmailId IN :ids ORDER BY f.id")
    List<Object[]> findInfoByInboundIds(@Param("ids") Collection<Long> ids);
}
