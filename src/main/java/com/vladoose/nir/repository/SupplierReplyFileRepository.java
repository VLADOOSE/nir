package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.entity.SupplierReplyFile;
import com.vladoose.nir.service.supplierreply.ReplyFileInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierReplyFileRepository extends JpaRepository<SupplierReplyFile, Long> {

    /** Тот же файл в том же запросе хранится один раз (сравнение по SHA-256). */
    boolean existsByPriceRequestIdAndSha256(Long priceRequestId, String sha256);

    @Query("SELECT new com.vladoose.nir.service.supplierreply.ReplyFileInfo(f.id, f.fileName, f.mimeType, f.sizeBytes, "
            + "f.kind, f.parseStatus, f.parseNote, f.pageCount, f.inboundEmailId, f.uploadedBy, f.createdAt) "
            + "FROM SupplierReplyFile f WHERE f.priceRequestId = :prId ORDER BY f.id")
    List<ReplyFileInfo> findInfo(@Param("prId") Long prId);

    /**
     * Только таблица файла, без байтов. Тип результата — Object: с типом-записью Spring Data 3.5 переписывает
     * запрос в конструктор «new ReplyTable(f.parsed)» (проекция DTO), а такого конструктора у записи нет.
     */
    @Query("SELECT f.parsed FROM SupplierReplyFile f WHERE f.id = :id")
    List<Object> findParsedRaw(@Param("id") Long id);

    default Optional<ReplyTable> findParsed(Long id) {
        return findParsedRaw(id).stream().filter(ReplyTable.class::isInstance).map(ReplyTable.class::cast).findFirst();
    }

    /** Имена файлов по письмам — колонка «Входящих» (одним запросом на страницу). */
    @Query("SELECT f.inboundEmailId, f.fileName FROM SupplierReplyFile f WHERE f.inboundEmailId IN :ids ORDER BY f.id")
    List<Object[]> findNamesByInboundIds(@Param("ids") Collection<Long> ids);
}
