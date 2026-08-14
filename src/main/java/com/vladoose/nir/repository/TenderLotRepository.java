package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface TenderLotRepository extends JpaRepository<TenderLot, Long> {

    List<TenderLot> findByTenderId(Long tenderId);

    List<TenderLot> findByEquipmentTypeId(Long equipmentTypeId);

    /**
     * Лоты в очереди на фоновый разбор техспеки. Рынок передаётся ЯВНО: воркер — фоновый поток,
     * привязанной сессии у него нет, и рыночный аспект не сработает (CLAUDE.md §6).
     */
    @Query("SELECT l.id FROM TenderLot l WHERE l.techSpecStatus = 'PENDING' "
         + "AND l.tender.market = :market ORDER BY l.id DESC")
    List<Long> findPendingTechSpec(@Param("market") Market market, Pageable pageable);

    /**
     * То же, но только по одной площадке. Нужно, когда качать вторую нечем: без токена goszakup
     * его лоты нельзя ни разобрать, ни честно списать — иначе один незаданный env навсегда
     * пометил бы 1300 лотов как «файла нет».
     */
    @Query("SELECT l.id FROM TenderLot l WHERE l.techSpecStatus = 'PENDING' "
         + "AND l.tender.market = :market AND l.tender.platform = :platform ORDER BY l.id DESC")
    List<Long> findPendingTechSpecByPlatform(@Param("market") Market market,
                                            @Param("platform") TenderPlatform platform,
                                            Pageable pageable);

    /**
     * Вернуть в очередь лоты, сгоревшие на недоступности площадки давнее {@code before}:
     * ERROR — это состояние ВНЕШНЕГО МИРА (IP-блок, обрыв сети), а не свойство лота, поэтому
     * конечным быть не должно. Зовётся через {@link com.vladoose.nir.service.TechSpecStatusWriter}
     * (отдельный бин с транзакцией — фоновому потоку нужна привязанная сессия, §6).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TenderLot l SET l.techSpecStatus = 'PENDING' "
         + "WHERE l.techSpecStatus = 'ERROR' AND l.techSpecAttemptedAt < :before")
    int requeueStaleErrors(@Param("before") OffsetDateTime before);

    @Query("SELECT l.equipmentType.name, COUNT(l) FROM TenderLot l WHERE l.equipmentType IS NOT NULL AND l.tender.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER GROUP BY l.equipmentType.name ORDER BY COUNT(l) DESC")
    List<Object[]> countByEquipType();
}
