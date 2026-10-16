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
            + "f.kind, f.parseStatus, f.parseNote, f.pageCount, f.inboundEmailId, f.uploadedBy, f.createdAt, f.rate, f.rateCurrency) "
            + "FROM SupplierReplyFile f WHERE f.priceRequestId = :prId ORDER BY f.id")
    List<ReplyFileInfo> findInfo(@Param("prId") Long prId);

    /** Курсы этой валюты, вписанные в файлах запроса, — [курс, когда вписан], последний первым (новый файл берёт его). */
    @Query("SELECT f.rate, f.rateSetAt FROM SupplierReplyFile f WHERE f.priceRequestId = :prId AND f.rateCurrency = :currency "
            + "AND f.rate IS NOT NULL ORDER BY f.rateSetAt DESC NULLS LAST, f.id DESC")
    List<Object[]> findRatesOfRequest(@Param("prId") Long prId, @Param("currency") String currency);

    /** Последний курс, вписанный для этой валюты на рынке (подсказка в поле курса), — [курс, когда вписан]; не было — пусто. */
    @Query(value = "SELECT f.rate, f.rate_set_at FROM supplier_reply_file f JOIN price_request pr ON pr.id = f.price_request_id "
            + "WHERE pr.market = :market AND f.rate_currency = :currency AND f.rate IS NOT NULL "
            + "ORDER BY f.rate_set_at DESC NULLS LAST, f.id DESC LIMIT 1", nativeQuery = true)
    List<Object[]> findLastRate(@Param("market") String market, @Param("currency") String currency);

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
