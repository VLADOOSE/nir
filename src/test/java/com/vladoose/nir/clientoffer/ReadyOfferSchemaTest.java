package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.ClientOfferRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V33 (спека 2026-10-19 §5): КП из файла без номера, его файл, отметка «Не КП» у файла переписки. */
@SpringBootTest
@Transactional
class ReadyOfferSchemaTest {

    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferFileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private ClientOffer fileOffer() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setNumber(null);
        o.setOrigin(ClientOfferOrigin.FILE);
        return offers.saveAndFlush(o);
    }

    private ClientOfferFile file(Long offerId) {
        return files.saveAndFlush(ClientOfferFile.builder().offerId(offerId).fileName("КП West-Med.docx").sizeBytes(3)
                .sha256("a".repeat(64)).content(new byte[]{1, 2, 3}).kind(ReplyFileKind.WORD)
                .parseStatus(ReplyParseStatus.PARSED).fileTotal(new BigDecimal("370860.00")).build());
    }

    @Test
    void fileOfferWithoutNumberAndItsFileAreStored() {
        ClientOffer o = fileOffer();
        file(o.getId());
        em.clear();

        ClientOffer back = offers.findById(o.getId()).orElseThrow();
        assertThat(back.getNumber()).isNull();
        assertThat(back.getOrigin()).isEqualTo(ClientOfferOrigin.FILE);
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getContent()).containsExactly(1, 2, 3);
        List<ClientOfferFileMeta> meta = files.findMetaByOfferIds(List.of(o.getId()));
        assertThat(meta).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("КП West-Med.docx");
            assertThat(m.kind()).isEqualTo(ReplyFileKind.WORD);
            assertThat(m.fileTotal()).isEqualByComparingTo("370860");
        });
    }

    @Test
    void builderIsDefaultOrigin_fileGoesWithItsOffer() {
        ClientOffer built = offers.saveAndFlush(ClientOfferTestData.newOffer(7));
        assertThat(built.getOrigin()).isEqualTo(ClientOfferOrigin.BUILDER);
        assertThat(jdbc.queryForObject("select origin from client_offer where id = ?", String.class, built.getId()))
                .isEqualTo("BUILDER");

        ClientOffer o = fileOffer();
        file(o.getId());
        jdbc.update("delete from client_offer where id = ?", o.getId());
        assertThat(jdbc.queryForObject("select count(*) from client_offer_file where offer_id = ?", Integer.class, o.getId()))
                .isZero();
    }

    @Test
    void attachmentHintFlagNotNullFalseByDefault() {
        assertThat(jdbc.queryForObject("""
                select is_nullable || ' ' || column_default from information_schema.columns
                where table_name = 'chat_attachment' and column_name = 'offer_hint_dismissed'""", String.class))
                .isEqualTo("NO false");
    }
}
