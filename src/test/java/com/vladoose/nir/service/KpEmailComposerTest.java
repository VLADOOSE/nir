package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Композиция письма КП против ВСТРОЕННОГО дефолта (шаблон рынка застаблен пустым → фолбэк на
 * DEFAULT_*). Детерминированно, без БД — не зависит от сохранённого оператором шаблона
 * (раньше @SpringBootTest читал реальный email_template и падал при кастомизации оператором).
 */
class KpEmailComposerTest {

    KpEmailComposer composer;

    @BeforeEach
    void setUp() {
        EmailTemplateRepository repo = mock(EmailTemplateRepository.class);
        when(repo.findByMarket(any())).thenReturn(Optional.empty());   // нет сохранённого → дефолт
        composer = new KpEmailComposer(repo, new EmailTemplateRenderer());
    }

    private PriceRequest kzTenderPr(String spec) {
        Tender t = new Tender();
        t.setTenderNumber("17276387-1");
        t.setSource(Source.PUBLIC_TENDER);
        t.setDeadline(LocalDate.of(2026, 7, 15));
        t.setSourceExtId("17276387-1"); // импорт кладёт полный номер с суффиксом лота

        Distributor d = new Distributor();
        d.setName("ZZ Дистр");
        d.setLastName("Иванов");
        d.setFirstName("Пётр");

        MedRegistry reg = new MedRegistry();
        reg.setRegNumber("РК-МТ-5№012345");
        MedEquipment eq = new MedEquipment();
        eq.setName("SonoMax DC-70");
        eq.setManufact("Mindray");
        eq.setRegistration(reg);

        TenderLot lot1 = new TenderLot();
        lot1.setLotNumber(1);
        lot1.setEquipName("Аппарат УЗИ");
        TenderLot lot2 = new TenderLot();
        lot2.setLotNumber(3);
        lot2.setEquipName("Аппарат ИВЛ");
        lot2.setRequiredSpec(spec);

        PriceRequestItem i1 = new PriceRequestItem();
        i1.setTenderLot(lot1);
        i1.setMedEquipment(eq);
        i1.setRequestedQuantity(2);
        PriceRequestItem i2 = new PriceRequestItem();
        i2.setTenderLot(lot2);
        i2.setRequestedQuantity(1);

        PriceRequest pr = new PriceRequest();
        pr.setId(42L);
        pr.setTender(t);
        pr.setDistributor(d);
        pr.setMarket(Market.KZ);
        pr.setItems(new ArrayList<>(List.of(i1, i2)));
        return pr;
    }

    @Test
    void kzTender_modelAndBareLot() {
        KpEmailComposer.Composed msg = composer.compose(kzTenderPr("Требуемая спека ИВЛ"));

        assertThat(msg.subject()).contains("[КП-42]").contains("Запрос коммерческого предложения");
        assertThat(msg.body())
                .contains("Уважаемый(ая) Иванов Пётр!")
                .contains("ТОО «West-Med»")
                .contains("— SonoMax DC-70 (Mindray), РУ № РК-МТ-5№012345 — 2 шт.")
                .contains("— Аппарат ИВЛ — 1 шт.")
                .contains("Требования: Требуемая спека ИВЛ")
                .contains("Просим ответить до 15.07.2026");

        // анти-лик: письмо не выдаёт, что мы ищем под тендер — ни «Лот N:», ни слова «тендер»,
        // ни номера объявления, ни названия реестра (оператор убрал строку про реестр).
        assertThat(msg.body())
                .doesNotContain("Лот ")
                .doesNotContain("НЦЭЛС РК")
                .doesNotContain("goszakup")
                .doesNotContain("тендер");
        assertThat(msg.subject()).doesNotContain("17276387");
    }

    @Test
    void goszakupSpec_requirementsSanitized_noTenderNumberNorAddress() {
        // голый лот с сырым goszakup-ТЗ: «Требования:» должны нести тех.характеристики, но НЕ
        // раскрывать тендер (номер закупки/лота, место поставки) — иначе поставщик пойдёт участвовать сам.
        String goszakupTz = "Приложение 2 Номер закупки: № 17295275-1 "
                + "Наименование закупки: Аппарат ультразвуковой оториноларингологический "
                + "Номер лота: № 84998390-ЗЦП1 Наименование лота: Аппарат "
                + "Описание лота: ультразвуковой Дополнительное описание лота: Аппарат ЛОР "
                + "Количество: 1 Единица измерения: Штука "
                + "Места поставки: 751710000, г.Алматы, Медеуский район "
                + "Срок поставки: до 31.12.2026 года "
                + "характеристики закупаемых товаров: Аппарат низкочастотный 26,5 кГц, блок управления, таймер";
        KpEmailComposer.Composed msg = composer.compose(kzTenderPr(goszakupTz));

        assertThat(msg.body()).contains("Требования:").contains("26,5 кГц"); // тех.суть сохранена
        assertThat(msg.body())
                .doesNotContain("17295275")          // номер закупки
                .doesNotContain("84998390")          // номер лота
                .doesNotContain("751710000")         // код места поставки
                .doesNotContain("Места поставки")
                .doesNotContain("Срок поставки");
    }

    @Test
    void longSpecTrimmedAt1200() {
        String longSpec = "х".repeat(2000);
        KpEmailComposer.Composed msg = composer.compose(kzTenderPr(longSpec));
        assertThat(msg.body()).contains("(полное ТЗ — по запросу)");
        assertThat(msg.body()).doesNotContain("х".repeat(1300));
    }

    @Test
    void rfBranding() {
        PriceRequest pr = kzTenderPr(null);
        pr.setMarket(Market.RF);
        pr.getTender().setSourceExtId(null);
        KpEmailComposer.Composed msg = composer.compose(pr);
        assertThat(msg.body())
                .contains("ООО «РЕГИОН-МЕД»")
                .doesNotContain("НЦЭЛС РК")
                .doesNotContain("Росздравнадзора");   // реестр убран из дефолта (оба рынка)
        // анти-лик: без ссылки на площадку закупок и без слова «тендер»
        assertThat(msg.body()).doesNotContain("zakupki.gov.ru").doesNotContain("тендер");
    }

    @Test
    void privateRequest_noAnnounceLinkNoTender() {
        PriceRequest pr = kzTenderPr(null);
        pr.getTender().setSource(Source.PRIVATE_REQUEST);
        pr.getTender().setTenderNumber("ЧЗ-2026-0007");
        pr.getTender().setSourceExtId(null);
        pr.getTender().setDeadline(null);
        KpEmailComposer.Composed msg = composer.compose(pr);
        assertThat(msg.subject()).contains("Запрос коммерческого предложения").doesNotContain("ЧЗ-2026-0007");
        assertThat(msg.body())
                .doesNotContain("Объявление:")
                .doesNotContain("Просим ответить до")
                .doesNotContain("ЧЗ-2026-0007")
                .doesNotContain("тендер");
    }
}
