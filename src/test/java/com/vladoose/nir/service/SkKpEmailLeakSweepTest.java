package com.vladoose.nir.service;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.util.LotDescriptiveText;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Свип по ЖИВЫМ разобранным ТЗ СК-Фармации в nirdb: письмо КП не должно нести идентификатор площадки.
 * Код лота («4875083-Т1») — это адрес объявления fms.ecc.kz/ru/announce/index/&lt;id&gt;, то есть
 * ровно то раскрытие тендера, которое анти-лик §9 из письма убрал. Тело письма собирается настоящим
 * {@link KpEmailComposer} (не приближением), сущности не персистятся — тест read-only.
 * На базе без импортированных SK-тендеров проверять нечего и свип проходит вхолостую; жёсткая
 * гарантия на всех формах шапки закреплена фикстурами в {@code LotDescriptiveTextTest}.
 */
@SpringBootTest
@Transactional
class SkKpEmailLeakSweepTest {

    /** Код лота площадки: 6–9 цифр + «-Т<цифра>», пробелы вокруг дефиса реальны («Лот 4875003 -Т1»). */
    private static final Pattern PLATFORM_CODE = Pattern.compile("(?<!\\d)\\d{6,9}\\s*-\\s*[ТT]\\d");

    /** Место назначения поставки из коммерческого блока ТЗ (оно же адрес заказчика). */
    private static final Pattern DELIVERY_MARKER = Pattern.compile(
            "ИНКОТЕРМС|DDP\\s+пункт|пункт[а-я]*\\s+назначения|мест[а-я]*\\s+дислокации|Адрес\\s*:",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * Орг-форма заказчика. «ТОО» сюда НЕ входит намеренно: {@code Market.KZ.companyShortName()} —
     * «ТОО «West-Med»», то есть наша собственная подпись в каждом письме.
     */
    private static final Pattern CUSTOMER_MARKER = Pattern.compile(
            "ГКП\\s+на\\s+ПХВ|КГП\\s+на\\s+ПХВ|ГККП|Некоммерческое\\s+акционерное"
                    + "|Акционерное\\s+общество|Коммунальное\\s+государственное",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @PersistenceContext EntityManager em;
    @Autowired KpEmailComposer composer;

    @Test
    void everyParsedSkSpec_producesLetterWithoutPlatformIdentifier() {
        List<TenderLot> lots = parsedSkLots();

        List<String> leaks = new ArrayList<>();
        for (TenderLot lot : lots) {
            String body = letterBodyFor(lot);
            String announceId = announceIdOf(lot);
            if (lot.getSourceLotCode() != null && !lot.getSourceLotCode().isBlank()) {
                // цифровая часть кода — именно она адресует объявление
                String digits = lot.getSourceLotCode().split("-")[0];
                if (body.contains(digits)) leaks.add("лот " + lot.getId() + ": код " + digits);
            }
            if (announceId != null && body.contains(announceId)) {
                leaks.add("лот " + lot.getId() + ": № объявления " + announceId);
            }
            if (PLATFORM_CODE.matcher(body).find()) {
                leaks.add("лот " + lot.getId() + ": остаток кода лота площадки");
            }
        }
        assertThat(leaks).as("идентификаторы площадки в теле письма КП (проверено ТЗ: %d)", lots.size())
                .isEmpty();
    }

    /**
     * Тот же свип на второе раскрытие: имя и адрес заказчика из коммерческого блока ТЗ. Оно прямее
     * кода лота — зная больницу и предмет закупки, поставщик находит объявление сам.
     * <p>Проверяются ДВА текста: тело письма (сквозной путь) и ПОЛНЫЙ санитизированный текст ТЗ до
     * обрезки {@code KpEmailComposer.SPEC_LIMIT}. Второй важнее: до среза хвоста заказчик не утекал
     * лишь потому, что таблица требований длиннее лимита (запас 2459 против 1200) — свойство данных,
     * а не кода. Утверждение на необрезанном тексте эквивалентно «лимит поднят до бесконечности»,
     * поэтому поднять {@code SPEC_LIMIT} позже уже нельзя так, чтобы вернуть утечку.
     */
    @Test
    void everyParsedSkSpec_producesLetterWithoutCustomerOrDeliveryPlace() {
        List<TenderLot> lots = parsedSkLots();

        List<String> leaks = new ArrayList<>();
        for (TenderLot lot : lots) {
            check(leaks, lot, "тело письма", letterBodyFor(lot));
            check(leaks, lot, "ТЗ до обрезки SPEC_LIMIT",
                    LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec()));
        }
        assertThat(leaks).as("заказчик/адрес/место поставки в письме КП (проверено ТЗ: %d)", lots.size())
                .isEmpty();
    }

    private void check(List<String> leaks, TenderLot lot, String where, String text) {
        if (DELIVERY_MARKER.matcher(text).find()) {
            leaks.add("лот " + lot.getId() + " (" + where + "): маркер места поставки");
        }
        if (CUSTOMER_MARKER.matcher(text).find()) {
            leaks.add("лот " + lot.getId() + " (" + where + "): орг-форма заказчика");
        }
    }

    private List<TenderLot> parsedSkLots() {
        return em.createQuery(
                        "select l from TenderLot l join l.tender t "
                                + "where t.platform = :p and l.requiredSpec is not null", TenderLot.class)
                .setParameter("p", TenderPlatform.SK_PHARMACY)
                .getResultList();
    }

    /** Настоящее тело письма для лота без предложенной модели — тот случай, где рендерится «Требования:». */
    private String letterBodyFor(TenderLot lot) {
        Distributor d = new Distributor();
        d.setName("ТОО Тест");
        PriceRequest pr = new PriceRequest();
        pr.setTender(lot.getTender());
        pr.setDistributor(d);
        pr.setMarket(Market.KZ);
        PriceRequestItem it = new PriceRequestItem();
        it.setPriceRequest(pr);
        it.setTenderLot(lot);
        it.setRequestedQuantity(1);
        pr.setItems(List.of(it));
        return composer.composeForPreview(pr).body();
    }

    private String announceIdOf(TenderLot lot) {
        String ext = lot.getTender().getSourceExtId();
        if (ext == null || ext.isBlank()) return null;
        String id = ext.split("-")[0];
        return id.isBlank() ? null : id;
    }
}
