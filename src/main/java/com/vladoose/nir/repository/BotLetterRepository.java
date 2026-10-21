package com.vladoose.nir.repository;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BotLetterRepository extends JpaRepository<BotLetter, Long> {

    List<BotLetter> findByListMessageIdOrderByLineNo(Long listMessageId);

    Optional<BotLetter> findFirstByPriceRequestId(Long priceRequestId);

    /** Перевод статуса «если сейчас один из from»: из одновременных «Отправить» 1 получит ровно одно (спека §7). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BotLetter b SET b.status = :to WHERE b.id = :id AND b.status IN :from")
    int transition(@Param("id") Long id, @Param("from") Collection<BotLetterStatus> from, @Param("to") BotLetterStatus to);
}
