package com.vladoose.nir.rates;

import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.OfficialRate;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CurrencyRateRepository;
import com.vladoose.nir.repository.OfficialRateRepository;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Наш курс и курс сегодня (спека §4.1–4.2). Строки курса — общие для рынка: в откатываемой транзакции сперва чистим. */
@SpringBootTest
@Transactional
class CurrencyRatesTest {

    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    CurrencyRates rates;

    /** 23.10.2026 10:00 по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        rates = new CurrencyRates(ours, official, true, CLOCK);
    }

    private void nbrk(String currency, LocalDate date, String rate) {
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency(currency).rateDate(date)
                .rate(new BigDecimal(rate)).fetchedAt(OffsetDateTime.now()).build());
    }

    @Test
    void save_validatesAllBeforeWriting() {
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")),
                new CurrencyRatesRequest.Entry("GBP", new BigDecimal("600")))), "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Валюта «GBP» — не для этого рынка");
        assertThat(rates.our(Market.KZ, "RUB")).isEmpty();                 // ни одной записи: проверка — до записи
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")),
                new CurrencyRatesRequest.Entry("rub", new BigDecimal("7")))), "vlad")).hasMessage("Валюта RUB — дважды");
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", BigDecimal.ZERO))), "vlad")).hasMessage("Курс — число больше нуля");
    }

    @Test
    void save_upsertAndRemove() {
        CurrencyRatesResponse r = rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB").orElseThrow()).isEqualByComparingTo("6");
        assertThat(r.rates()).extracting(CurrencyRatesResponse.Row::currency).containsExactly("RUB", "USD", "EUR", "CNY");
        assertThat(r.rates().get(0).updatedBy()).isEqualTo("vlad");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6.5")))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB").orElseThrow()).isEqualByComparingTo("6.5");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", null))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB")).isEmpty();
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("USD", new BigDecimal("480")))), "vlad");
        assertThat(rates.our(Market.RF, "USD")).isEmpty();               // рынки раздельно: курс KZ рынку РФ не виден
        assertThat(rates.our(Market.RF, "RUB")).contains(BigDecimal.ONE);  // у РФ рубль — валюта рынка
        assertThat(rates.our(Market.KZ, "KZT")).contains(BigDecimal.ONE);  // валюта рынка — 1
    }

    @Test
    void today_lastNotAfterToday_staleAfter3Days() {
        nbrk("RUB", LocalDate.of(2026, 10, 22), "5.48");
        nbrk("RUB", LocalDate.of(2026, 10, 24), "5.50");                   // завтрашний — не сегодня
        CurrencyRates.Today t = rates.today(Market.KZ, "RUB").orElseThrow();
        assertThat(t.rate()).isEqualByComparingTo("5.48");
        assertThat(t.date()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(t.stale()).isFalse();
        nbrk("USD", LocalDate.of(2026, 10, 19), "448");
        assertThat(rates.today(Market.KZ, "USD").orElseThrow().stale()).isTrue();
        assertThat(rates.today(Market.RF, "USD")).isEmpty();               // у РФ курса сегодня нет
        assertThat(rates.today(Market.KZ, "KZT")).isEmpty();
    }

    @Test
    void view_marginToToday() {
        nbrk("RUB", LocalDate.of(2026, 10, 23), "5.48");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        CurrencyRatesResponse.Row rub = rates.view(Market.KZ).rates().get(0);
        assertThat(rub.today().rate()).isEqualByComparingTo("5.48");
        assertThat(rub.marginPct()).isEqualByComparingTo("9.5");
        assertThat(rates.view(Market.RF).rates()).extracting(CurrencyRatesResponse.Row::currency).containsExactly("USD", "EUR", "CNY", "KZT");
    }
}
