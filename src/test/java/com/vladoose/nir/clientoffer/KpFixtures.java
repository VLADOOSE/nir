package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.document.KpDocumentBuilder;
import com.vladoose.nir.service.offer.ClientOfferCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Реквизиты West-Med и КП «как от 24.09» без БД — для тестов модели документа и рендереров. */
final class KpFixtures {

    private KpFixtures() {}

    static CompanyProfile profileKz() {
        return CompanyProfile.builder()
                .market(Market.KZ)
                .shortName("ТОО «West-Med»")
                .fullName("Товарищество с ограниченной ответственностью «West-Med»")
                .headerLeft("Жауапкершілігі\nшектеулі серіктестігі")
                .headerRight("Товарищество\nс ограниченной ответственностью")
                .brandText("\"West-Med\"")
                .idsLine("РНН 271 800 059 535 БИН 121 040 000 303")
                .binInn("121040000303")
                .address("Республика Казахстан, 090000, Западно-Казахстанская область,\nгород Уральск, ул.Мухита 121-21")
                .accounts("KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)")
                .bankName("АО \"Alatau City Bank\"")
                .bik("TSESKZKA")
                .phone("87770752770")
                .email("west-med@mail.ru")
                .directorTitle("Директор")
                .directorName("Ширяев Илья Викторович")
                .signoffContacts("моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru")
                .vatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)))
                .vatDefault(new BigDecimal("5"))
                .vatRegistered(new BigDecimal("5"))
                .vatNotRegistrable(new BigDecimal("16"))
                .defaultMarkupPct(new BigDecimal("20"))
                .stampSizeMm(40)
                .build();
    }

    static List<OfferColumn> kzColumns() {
        return new ArrayList<>(List.of(
                new OfferColumn("NUM", "№"), new OfferColumn("NAME", "Наименование"), new OfferColumn("UNIT", "Ед. изм."),
                new OfferColumn("QTY", "Кол-во"), new OfferColumn("PRICE", "Цена за ед., тг"), new OfferColumn("VAT_RATE", "НДС"),
                new OfferColumn("SUM", "Общая сумма, тг"), new OfferColumn("REGISTRATION", "Регистрация в РК")));
    }

    /** КП № 443 от 14.09.2026: 4 строки из КП отца от 24.09 (цены вбиты руками), условия — списком. */
    static ClientOffer offer2409() {
        ClientOffer o = ClientOfferTestData.newOffer(443);
        o.setMarket(Market.KZ);
        o.setTableColumns(kzColumns());
        o.setIntro("ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:");
        o.setTerms(new ArrayList<>(List.of(
                new OfferTerm("", "Цены действительны в течение 10 дней"),
                new OfferTerm("", "Транспортные услуги включены в общую стоимость товара"),
                new OfferTerm("Порядок оплаты", "100% предоплата"),
                new OfferTerm("Форма оплаты", "безналичная"),
                new OfferTerm("Срок поставки всего товара", "30 рабочих дней после поступления предоплаты"))));
        line(o, "Пульсоксиметр QMP – PO70 взрослый", 3, "105600.00", "5",
                OfferRegistrationStatus.MANUAL, "№ РК-МИ (МТ)-0№023037 от 28.10.2021 г.");
        line(o, "Гигрометр психрометрический ВИТ-2", 4, "13515.00", "16",
                OfferRegistrationStatus.NOT_REQUIRED, "Не подлежит регистрации");
        line(o, "Мешок для ИВЛ типа «Амбу» Beebrix, 1600 мл", 2, "21150.00", "5",
                OfferRegistrationStatus.MANUAL, "№ РК МИ (ИМН)-0 №027522 бессрочно");
        line(o, "Термоконтейнер для холодовой цепи ТМ-4", 4, "83725.00", "16",
                OfferRegistrationStatus.NOT_REQUIRED, "Не подлежит регистрации");
        return o;
    }

    static ClientOfferItem line(ClientOffer o, String name, int qty, String price, String vat,
                                OfferRegistrationStatus reg, String regText) {
        ClientOfferItem it = ClientOfferTestData.item(o, o.getItems().size() + 1, name, null, vat);
        it.setQuantity(BigDecimal.valueOf(qty));
        it.setPriceOverride(new BigDecimal(price));
        it.setRegistrationStatus(reg);
        it.setRegistrationText(regText);
        o.getItems().add(it);
        return it;
    }

    static KpDocument document(ClientOffer offer, CompanyProfile profile) {
        return new KpDocumentBuilder().build(offer, profile, new ClientOfferCalculator().calculate(offer));
    }
}
