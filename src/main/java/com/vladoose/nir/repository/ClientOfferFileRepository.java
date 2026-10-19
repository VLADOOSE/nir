package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.ClientOfferFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientOfferFileRepository extends JpaRepository<ClientOfferFile, Long> {

    Optional<ClientOfferFile> findByOfferId(Long offerId);

    /** Сведения без байтов и таблиц — одним запросом на КП сделки / журнала. */
    @Query("""
           select new com.vladoose.nir.dto.response.ClientOfferFileMeta(f.offerId, f.fileName, f.kind, f.sizeBytes,
               f.parseStatus, f.parseNote, f.pages, f.fileTotal, f.chatAttachmentId)
           from ClientOfferFile f where f.offerId in :ids""")
    List<ClientOfferFileMeta> findMetaByOfferIds(@Param("ids") Collection<Long> ids);
}
