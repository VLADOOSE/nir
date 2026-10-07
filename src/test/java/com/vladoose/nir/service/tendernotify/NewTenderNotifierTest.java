package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.TenderRepository;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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

    final List<String> sent = new ArrayList<>();
    RuntimeException failNext;

    @BeforeEach
    void kz() { MarketContext.set(Market.KZ); }

    @AfterEach
    void clear() { MarketContext.clear(); }

    NewTenderNotifier notifier(boolean enabled, TelegramSettings tg, String regions) {
        return notifier(tenderRepository, enabled, tg, regions);
    }

    NewTenderNotifier notifier(TenderRepository repo, boolean enabled, TelegramSettings tg, String regions) {
        return new NewTenderNotifier(repo, txManager, tg, text -> {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
            sent.add(text);
        }, enabled, regions, "https://ais.westmed.kz", CLOCK);
    }

    Tender tender(String ext, String region, String status, LocalDate deadline) {
        tenderRepository.findBySourceExtId(ext).ifPresent(tenderRepository::delete);
        Tender t = new Tender();
        t.setSourceExtId(ext);
        t.setTenderNumber(ext);
        t.setPlatform(TenderPlatform.GOSZAKUP);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setStatus(status);
        t.setDeadline(deadline);
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
}
