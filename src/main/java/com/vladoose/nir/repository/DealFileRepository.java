package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.DealFile;
import com.vladoose.nir.entity.DealFileStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DealFileRepository extends JpaRepository<DealFile, Long> {

    @Query("""
           select new com.vladoose.nir.dto.response.DealFileMeta(f.id, f.stage, f.paymentId, f.dealSupplierId, f.fileName,
               f.sizeBytes, f.sha256, f.uploadedBy, f.uploadedAt)
           from DealFile f where f.tenderId = :tenderId order by f.uploadedAt asc, f.id asc""")
    List<DealFileMeta> findMetaByTenderId(@Param("tenderId") Long tenderId);

    Optional<DealFile> findByIdAndTenderId(Long id, Long tenderId);

    boolean existsByTenderIdAndStage(Long tenderId, DealFileStage stage);
}
