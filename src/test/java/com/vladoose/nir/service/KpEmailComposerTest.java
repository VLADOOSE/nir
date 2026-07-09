package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KpEmailComposerTest {

    @Autowired KpEmailComposer composer;

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
                .contains("Лот 1: SonoMax DC-70 (Mindray), РУ № РК-МТ-5№012345 — 2 шт.")
                .contains("Лот 3: Аппарат ИВЛ — 1 шт.")
                .contains("Требования (из ТЗ): Требуемая спека ИВЛ")
                .contains("Просим ответить до 15.07.2026")
                .contains("НЦЭЛС РК")
                .doesNotContain("Росздравнадзор");

        // анти-лик: письмо не раскрывает конкретный тендер (нет номера/ссылки/слова «тендер»)
        assertThat(msg.body()).doesNotContain("goszakup").doesNotContain("тендер");
        assertThat(msg.subject()).doesNotContain("17276387");
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
                .contains("Росздравнадзора")
                .doesNotContain("НЦЭЛС РК");
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
