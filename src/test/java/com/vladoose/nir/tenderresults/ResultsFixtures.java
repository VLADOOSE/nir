package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Тендеры goszakup для тестов итогов: номер объявления «<id>-1», лоты с кодами площадки. */
public final class ResultsFixtures {

    private ResultsFixtures() {}

    /** lots — пары «код лота площадки, наименование». Имя другое, чем у {@link #tender}: две перегрузки с String... неоднозначны. */
    public static Tender tenderIn(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String region, String... lots) {
        Tender t = Tender.builder().tenderNumber(buyId + "-1").sourceExtId(buyId + "-1").status("COMPLETED")
                .source(Source.PUBLIC_TENDER).platform(TenderPlatform.GOSZAKUP).market(Market.KZ).currency("KZT")
                .description("ZZ закупка " + buyId).customerName("ГКП «ZZ больница»").customerBin("110240019426")
                .region(region).deadline(deadlineAt.toLocalDate()).deadlineAt(deadlineAt).build();
        for (int i = 0; i + 1 < lots.length; i += 2) {
            t.getLots().add(TenderLot.builder().tender(t).sourceLotCode(lots[i]).equipName(lots[i + 1]).quantity(3)
                    .maxCost(new BigDecimal("613900.00")).techSpecStatus(TechSpecStatus.PENDING).build());
        }
        return tenders.saveAndFlush(t);
    }

    public static Tender tender(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String... lots) {
        return tenderIn(tenders, buyId, deadlineAt, "Западно-Казахстанская область", lots);
    }

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 0);
    public static final LocalDate TODAY = NOW.toLocalDate();
}
