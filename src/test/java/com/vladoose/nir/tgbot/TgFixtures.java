package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Тестовые данные кнопок бота: тендер KZ с лотом, поставщик, наш товар, группа, отметка «наш товар», учётка. */
public final class TgFixtures {
    private TgFixtures() {}

    public static TenderLot lot(TenderRepository tenders, String lotName, String customer) {
        LocalDate day = LocalDate.now(Market.KZ.zone()).plusDays(10);
        Tender t = Tender.builder().tenderNumber("ZZ-ТГ-" + System.nanoTime()).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT").platform(TenderPlatform.GOSZAKUP).description("Закуп медицинских изделий")
                .customerName(customer).deadline(day).deadlineAt(day.atTime(10, 0)).build();
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName(lotName);
        l.setQuantity(1);
        t.getLots().add(l);
        tenders.saveAndFlush(t);
        return t.getLots().get(0);
    }

    public static Distributor distributor(DistributorRepository repo, String name, String email, boolean maker, boolean trusted,
                                          String... brands) {
        return repo.saveAndFlush(Distributor.builder().name(name + " " + System.nanoTime()).market(Market.KZ).email(email)
                .manufacturer(maker).trusted(trusted).brands(new ArrayList<>(List.of(brands))).build());
    }

    public static SiteProduct product(SiteProductRepository repo, String name, String brand) {
        return repo.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand(brand)
                .slug("zz-" + System.nanoTime()).syncedAt(OffsetDateTime.now()).build());
    }

    public static ProductGroup group(ProductGroupRepository repo) {
        return repo.saveAndFlush(ProductGroup.builder().market(Market.KZ).name("ZZ группа " + System.nanoTime())
                .searchWords(new ArrayList<>(List.of("дефибриллятор"))).position(990).build());
    }

    public static TenderLotProductMatch match(TenderLotProductMatchRepository repo, TenderLot lot, long groupId,
                                              ProductMatchLevel level, Long productId, List<Long> candidateIds) {
        List<FitCandidate> cands = candidateIds.stream().map(id -> new FitCandidate(id, "ZZ кандидат", "zz", FitVerdict.PARTIAL,
                1, 0, 2, 1, null, null, "ACTIVE", null, List.of())).toList();
        return repo.saveAndFlush(TenderLotProductMatch.builder().tenderId(lot.getTender().getId()).lotId(lot.getId())
                .groupId(groupId).word("дефибриллятор").level(level).productId(productId).fit(FitVerdict.PARTIAL)
                .fitCandidates(new ArrayList<>(cands)).source(ProductMatchSource.SEARCH).build());
    }

    public static UserAccount user(UserAccountRepository repo, String username, String role, Long telegramId) {
        return repo.saveAndFlush(UserAccount.builder().username(username + System.nanoTime()).role(role).passwordHash("x")
                .telegramUserId(telegramId).build());
    }
}
