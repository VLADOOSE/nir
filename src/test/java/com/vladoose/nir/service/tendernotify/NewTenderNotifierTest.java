package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.ProductWatchNotifier;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Кто попадает в уведомление: только созданные прогоном, действующие, со сроком не в прошлом и из нужного региона. */
@SpringBootTest
@Transactional
class NewTenderNotifierTest {

    static final String ZKO = "Западно-Казахстанская область";
    /** 2026-10-07 10:00 по Уральску (UTC+5). */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T05:00:00Z"), ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    static final TelegramSettings TG_ON = new TelegramSettings(true, "http://127.0.0.1:7798", "1:x", "-1001", "", "88");
    static final TelegramSettings TG_OFF = new TelegramSettings(false, "", "", "", "", "");

    @Autowired TenderRepository tenderRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ProductMatchQuery productQuery;
    @Autowired ProductMatchService productMatches;
    @Autowired TenderLotProductMatchRepository matchRepo;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository siteProducts;
    @Autowired EntityManager em;

    final List<String> sent = new ArrayList<>();
    /** Без звука ли ушло каждое сообщение (★ «наш товар» — со звуком, остальное тихо). */
    final List<Boolean> silent = new ArrayList<>();
    RuntimeException failNext;

    @BeforeEach
    void kz() { MarketContext.set(Market.KZ); }

    @AfterEach
    void clear() { MarketContext.clear(); }

    NewTenderNotifier notifier(boolean enabled, TelegramSettings tg, String regions) {
        return notifier(tenderRepository, enabled, tg, regions);
    }

    NewTenderNotifier notifier(TenderRepository repo, boolean enabled, TelegramSettings tg, String regions) {
        return new NewTenderNotifier(repo, txManager, tg, (text, quiet) -> {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
            sent.add(text);
            silent.add(quiet);
        }, productQuery, productMatches, enabled, regions, "https://ais.westmed.kz", CLOCK);
    }

    Tender tender(String ext, String region, String status, LocalDate deadline) {
        return tender(ext, region, status, deadline, null);
    }

    Tender tender(String ext, String region, String status, LocalDate deadline, LocalDateTime deadlineAt) {
        tenderRepository.findBySourceExtId(ext).ifPresent(tenderRepository::delete);
        Tender t = new Tender();
        t.setSourceExtId(ext);
        t.setTenderNumber(ext);
        t.setPlatform(TenderPlatform.GOSZAKUP);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setStatus(status);
        t.setDeadline(deadline);
        t.setDeadlineAt(deadlineAt);
        t.setRegion(region);
        t.setCustomerName("Больница " + ext);
        t.setDescription("Закуп " + ext);
        t.setTotalCost(new BigDecimal("100000"));
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName("Кушетка медицинская");
        l.setQuantity(2);
        t.getLots().add(l);
        return tenderRepository.saveAndFlush(t);
    }

    static ImportSummary created(String... ext) {
        ImportSummary s = new ImportSummary();
        for (String e : ext) s.addCreated(e);
        return s;
    }

    @Test
    void onlyActive_notExpired_inRegion_oneMessage() {
        Tender a = tender("NT-A", ZKO, "ACTIVE", TODAY.plusDays(2));
        tender("NT-B", ZKO, "COMPLETED", TODAY.plusDays(2));
        tender("NT-C", ZKO, "ACTIVE", TODAY.minusDays(1));                 // срок прошёл, статус ещё не закрыт
        tender("NT-D", "г. Астана", "ACTIVE", TODAY.plusDays(2));          // СК-Фармация: регион организатора
        Tender e = tender("NT-E", ZKO, "ACTIVE", TODAY);                   // срок сегодня — ещё принимают

        notifier(true, TG_ON, ZKO).afterImport(created("NT-A", "NT-B", "NT-C", "NT-D", "NT-E"));

        assertThat(sent).hasSize(1);
        String text = sent.get(0);
        assertThat(text).startsWith("🏥 Новые тендеры: 2\n")
                .contains("goszakup № NT-A").contains("goszakup № NT-E")
                .doesNotContain("NT-B").doesNotContain("NT-C").doesNotContain("NT-D")
                .contains("openId=" + a.getId() + "&market=KZ").contains("openId=" + e.getId() + "&market=KZ")
                .contains("Лоты (1): Кушетка медицинская — 2 шт.");
        assertThat(silent).containsExactly(true);                           // без нашего товара — тихо
    }

    ProductGroup ourGroup;

    /** Отметка «наш товар» на первом лоте тендера — как её ставит импорт (спека 2026-10-19-product-watch §8). */
    void mark(Tender t, ProductMatchLevel level, String productName) {
        if (ourGroup == null) {
            ourGroup = groupRepo.save(ProductGroup.builder().market(Market.KZ).name("NT: отсасыватели")
                    .searchWords(List.of("отсасыватель")).requireWords(List.of()).excludeWords(List.of()).brandWords(List.of())
                    .active(true).position(99).build());
        }
        Long productId = null;
        if (productName != null) {
            productId = siteProducts.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                    .name(productName).syncedAt(OffsetDateTime.now()).build()).getId();
        }
        matchRepo.save(TenderLotProductMatch.builder().tenderId(t.getId()).lotId(t.getLots().get(0).getId()).groupId(ourGroup.getId())
                .word("отсасыватель").level(level).evidence(level == ProductMatchLevel.COPIED ? "совпало 5 фраз: блок аспирации" : null)
                .productId(productId).source(ProductMatchSource.IMPORT).build());
        em.flush();
    }

    /**
     * Наш товар у нового тендера ЗКО (спека 2026-10-19-product-watch §9): строка в «🏥 Новый тендер», ★ — со звуком,
     * тендер сообщён — уведомитель нашей продукции второго сообщения о нём не шлёт.
     */
    @Test
    void ourProductCopied_lineWithSound_thenProductNotifierSilent() {
        Tender t = tender("NT-P", ZKO, "ACTIVE", TODAY.plusDays(2));
        mark(t, ProductMatchLevel.COPIED, "Аппарат для аспирации дыма АСД-«ФОТЕК». Набор с фильтрами");

        notifier(true, TG_ON, ZKO).afterImport(created("NT-P"));

        assertThat(sent).singleElement().asString().startsWith("🏥 Новый тендер\n")
                .contains("Лоты (1): Кушетка медицинская — 2 шт.\n★ ТЗ списано с нашего товара: Аппарат для аспирации дыма АСД-«ФОТЕК»\n");
        assertThat(silent).containsExactly(false);
        em.flush();
        em.clear();
        assertThat(tenderRepository.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.COPIED);

        List<String> productSent = new ArrayList<>();
        new ProductWatchNotifier(matchRepo, tenderRepository, productQuery, productMatches, txManager, TG_ON,
                (text, quiet) -> productSent.add(text), true, com.vladoose.nir.service.productwatch.ProductNotifyPolicy.of("all", "0"),
                "https://ais.westmed.kz", CLOCK).afterRun(new ImportSummary());
        assertThat(productSent).isEmpty();
    }

    /**
     * «✓ подходит» у нового тендера ЗКО — строкой и со звуком (спека 2026-10-20-product-spec-fit §6.4: FITS — сигнальный
     * уровень, как ★): второго сообщения о тендере в теме нашей продукции нет, без звука сигнал потерялся бы.
     */
    @Test
    void ourProductFits_lineWithSound() {
        Tender t = tender("NT-F", ZKO, "ACTIVE", TODAY.plusDays(2));
        mark(t, ProductMatchLevel.FITS, null);

        notifier(true, TG_ON, ZKO).afterImport(created("NT-F"));

        assertThat(sent).singleElement().asString().contains("\n✓ Подходит наш товар: отсасыватель");
        assertThat(silent).containsExactly(false);
    }

    /** Тендеры с нашим товаром — первыми; 🏷 «наш вид» — без звука. */
    @Test
    void ourProductTendersFirst_typeSilent() {
        tender("NT-Q1", ZKO, "ACTIVE", TODAY.plusDays(2));
        Tender ours = tender("NT-Q2", ZKO, "ACTIVE", TODAY.plusDays(2));
        mark(ours, ProductMatchLevel.TYPE, null);

        notifier(true, TG_ON, ZKO).afterImport(created("NT-Q1", "NT-Q2"));

        String text = sent.get(0);
        assertThat(text.indexOf("NT-Q2")).isLessThan(text.indexOf("NT-Q1"));
        assertThat(text).contains("\n🏷 Наш вид: отсасыватель\n");
        assertThat(silent).containsExactly(true);
    }

    /** Не поместившийся в сообщение тендер сообщённым не считается — его пришлёт уведомитель нашей продукции. */
    @Test
    void cutFromMessage_notMarkedNotified() {
        List<Tender> all = new ArrayList<>();
        String[] ext = new String[15];
        for (int i = 0; i < ext.length; i++) {
            ext[i] = "NT-R" + i;
            Tender t = tender(ext[i], ZKO, "ACTIVE", TODAY.plusDays(2));
            t.setDescription("Очень длинное наименование закупки медицинских изделий для нужд больницы ".repeat(3));
            tenderRepository.saveAndFlush(t);
            mark(t, ProductMatchLevel.TYPE, null);
            all.add(t);
        }

        notifier(true, TG_ON, ZKO).afterImport(created(ext));

        String text = sent.get(0);
        assertThat(text).contains("…и ещё");
        em.flush();
        em.clear();
        long marked = all.stream()
                .filter(t -> tenderRepository.findById(t.getId()).orElseThrow().getProductNotifiedLevel() != null).count();
        assertThat(marked).isGreaterThan(0).isLessThan(ext.length);
        assertThat(text.split("\n▸ ", -1).length - 1).as("сообщено ровно показанное").isEqualTo((int) marked);
    }

    /** B2: срок сегодня, но время приёма уже прошло — не «новый тендер», а закрытый; время — в тексте. */
    @Test
    void deadlineTimeAlreadyPassedToday_notNotified() {
        tender("NT-T1", ZKO, "ACTIVE", TODAY, TODAY.atTime(9, 0));          // CLOCK — 10:00 по Уральску
        tender("NT-T2", ZKO, "ACTIVE", TODAY, TODAY.atTime(18, 0));

        notifier(true, TG_ON, ZKO).afterImport(created("NT-T1", "NT-T2"));

        assertThat(sent).singleElement().asString()
                .contains("NT-T2").doesNotContain("NT-T1").contains("приём до 07.10.2026 18:00");
    }

    @Test
    void nothingQualifies_nothingSent() {
        tender("NT-F", ZKO, "COMPLETED", TODAY.plusDays(2));

        notifier(true, TG_ON, ZKO).afterImport(created("NT-F"));

        assertThat(sent).isEmpty();
    }

    /** Все регионы — только явной «*». */
    @Test
    void regionsStar_meansAllRegions() {
        tender("NT-G", "г. Астана", "ACTIVE", TODAY.plusDays(3));

        notifier(true, TG_ON, "*").afterImport(created("NT-G"));

        assertThat(sent).singleElement().asString().contains("NT-G");
    }

    /** Пустая переменная окружения (TENDERS_NOTIFY_REGIONS=) — ЗКО, а не вся страна: требование «только ЗКО». */
    @Test
    void regionsBlank_meansZkoOnly() {
        tender("NT-K", "г. Астана", "ACTIVE", TODAY.plusDays(3));
        tender("NT-L", ZKO, "ACTIVE", TODAY.plusDays(3));

        notifier(true, TG_ON, " , ").afterImport(created("NT-K", "NT-L"));

        assertThat(sent).singleElement().asString().contains("NT-L").doesNotContain("NT-K");
    }

    @Test
    void regionComparedIgnoringCaseAndSpaces() {
        tender("NT-H", "  западно-казахстанская область ", "ACTIVE", TODAY.plusDays(3));

        notifier(true, TG_ON, ZKO + ", Атырауская область").afterImport(created("NT-H"));

        assertThat(sent).singleElement().asString().contains("NT-H");
    }

    /** Telegram не принял — ошибка видна в итоге прогона, а уведомление уходит со следующим прогоном. */
    @Test
    void sendFailed_errorInSummary_retriedWithNextRun() {
        tender("NT-I", ZKO, "ACTIVE", TODAY.plusDays(2));
        NewTenderNotifier n = notifier(true, TG_ON, ZKO);
        failNext = new IllegalStateException("Telegram: HTTP 500");

        ImportSummary first = created("NT-I");
        n.afterImport(first);

        assertThat(sent).isEmpty();
        assertThat(first.getErrors()).isEqualTo(1);
        assertThat(first.getLastError()).startsWith("уведомление в Telegram о новых тендерах не ушло")
                .contains("Telegram: HTTP 500");

        n.afterImport(new ImportSummary());                                 // следующий прогон, новых нет

        assertThat(sent).singleElement().asString().contains("NT-I");
        n.afterImport(new ImportSummary());
        assertThat(sent).hasSize(1);                                        // отправленное не повторяется
    }

    /** База не ответила при сборке — новые номера этого прогона не теряются: следующий прогон увидит их как «обновлённые». */
    @Test
    void readFailed_newTendersRetriedWithNextRun() {
        tender("NT-M", ZKO, "ACTIVE", TODAY.plusDays(2));
        TenderRepository flaky = mock(TenderRepository.class);
        when(flaky.findBySourceExtId(anyString()))
                .thenThrow(new IllegalStateException("Connection refused"))
                .thenAnswer(inv -> tenderRepository.findBySourceExtId(inv.getArgument(0)));
        NewTenderNotifier n = notifier(flaky, true, TG_ON, ZKO);

        ImportSummary first = created("NT-M");
        n.afterImport(first);

        assertThat(sent).isEmpty();
        assertThat(first.getErrors()).isEqualTo(1);
        assertThat(first.getLastError()).startsWith("уведомление в Telegram о новых тендерах не собрано");

        n.afterImport(new ImportSummary());

        assertThat(sent).singleElement().asString().contains("NT-M");
    }

    /** Сбой уведомления не затирает причину сбоя самого импорта — она важнее. */
    @Test
    void notifyError_keepsImportErrorAsLast() {
        tender("NT-N", ZKO, "ACTIVE", TODAY.plusDays(2));
        failNext = new IllegalStateException("Telegram: HTTP 500");
        ImportSummary sum = created("NT-N");
        sum.addError("объявление 1-1: goszakup API недоступно: ConnectException");
        sum.setMessage("Больниц 1, получено 1, ошибок 1");

        notifier(true, TG_ON, ZKO).afterImport(sum);

        assertThat(sum.getErrors()).isEqualTo(2);
        assertThat(sum.getLastError()).isEqualTo("объявление 1-1: goszakup API недоступно: ConnectException");
        assertThat(sum.getMessage()).endsWith(" · Telegram: уведомление о новых тендерах не ушло");
    }

    @Test
    void disabledOrTelegramNotConfigured_nothingSent() {
        tender("NT-J", ZKO, "ACTIVE", TODAY.plusDays(2));

        notifier(false, TG_ON, ZKO).afterImport(created("NT-J"));
        notifier(true, TG_OFF, ZKO).afterImport(created("NT-J"));

        assertThat(sent).isEmpty();
    }

    @Test
    void unknownExtId_ignored() {
        notifier(true, TG_ON, ZKO).afterImport(created("NT-NO-SUCH"));

        assertThat(sent).isEmpty();
    }

    /** Строки рынка — по лотам тендера, вне транзакции чтения, в текст «Нового тендера». */
    @Test
    void marketLineInMessage() {
        Tender t = tender("ZZ-MKT-1", ZKO, "ACTIVE", TODAY.plusDays(2));
        NewTenderNotifier n = notifier(true, TG_ON, "");
        List<Map<Long, List<Long>>> asked = new ArrayList<>();
        n.setMarketLines(byTender -> {
            asked.add(byTender);
            return Map.of(t.getId(), "рынок: медиана 1,34 млн ₸ (69 дог.)");
        });
        n.afterImport(created("ZZ-MKT-1"));
        assertThat(asked.get(0).get(t.getId())).containsExactly(t.getLots().get(0).getId());
        assertThat(sent.get(0)).contains("\nрынок: медиана 1,34 млн ₸ (69 дог.)");
    }

    /** Строка «закупка по прайсу» — рядом со строкой рынка (спека 2026-10-23 §6.2 п. 4). */
    @Test
    void priceLineMergedWithMarketLine() {
        Tender t = tender("ZZ-PRC-1", ZKO, "ACTIVE", TODAY.plusDays(2));
        NewTenderNotifier n = notifier(true, TG_ON, "");
        n.setMarketLines(byTender -> Map.of(t.getId(), "рынок: медиана 1,34 млн ₸ (69 дог.)"));
        n.setPriceLines(byTender -> Map.of(t.getId(), "закупка по прайсу ≈ 900 000 ₸"));
        n.afterImport(created("ZZ-PRC-1"));
        assertThat(sent.get(0)).contains("\nрынок: медиана 1,34 млн ₸ (69 дог.) · закупка по прайсу ≈ 900 000 ₸");
    }
}
