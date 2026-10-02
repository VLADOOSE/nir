package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.offer.ColumnAlign;
import com.vladoose.nir.service.offer.OfferColumnKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Модель документа (спека §6): колонки, строки трёх видов, итоги, условия, бланк, подпись. */
class KpDocumentBuilderTest {

    /** Неразрывные пробелы между разрядами (DocFormat) — обычными, чтобы ожидания читались. */
    private static String nb(String s) {
        return s.replace('\u00A0', ' ');
    }

    private static List<String> labels(KpDocument d) {
        return d.columns().stream().map(KpDocument.Column::label).toList();
    }

    @Test
    void columnsKeepCustomLabelsAndFillDefaults() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null),
                new OfferColumn("NAME", "Товары (работы, услуги)"), new OfferColumn("PRICE", null),
                new OfferColumn("VAT_RATE", " "), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(labels(d)).containsExactly("№", "Товары (работы, услуги)", "Цена (с НДС)", "Ставка НДС", "Сумма (с НДС)");
        assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(d.columns().get(1).percent()).isGreaterThan(40);
        assertThat(d.columns().get(2).align()).isEqualTo(ColumnAlign.RIGHT);
    }

    @Test
    void vatOffHidesVatColumnsAndSaysWithoutVat() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("PRICE", null),
                new OfferColumn("VAT_RATE", null), new OfferColumn("VAT_SUM", null), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(labels(d)).containsExactly("Наименование", "Цена", "Сумма");
        assertThat(d.totalLines()).contains("Без НДС");
    }

    @Test
    void unknownColumnIsSkippedAndNameIsAlwaysPresent() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("FOO", "x"), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.columns()).extracting(KpDocument.Column::key).containsExactly("NAME", "SUM");
    }

    @Test
    void nameGetsModelAndProducerWhenTheirColumnsAreHidden() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem it = ClientOfferTestData.item(o, 1, "Скальпель офтальмологический", "1000", "5");
        it.setModel("MSL24");
        it.setManufacturer("Mani");
        it.setCountry("Вьетнам");
        o.getItems().add(it);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        int name = 1; // NUM, NAME, SUM
        assertThat(d.rows().get(0).cells().get(name))
                .containsExactly("Скальпель офтальмологический MSL24", "Производитель: Mani, Вьетнам");

        o.getTableColumns().add(new OfferColumn("MODEL", null));
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.rows().get(0).cells().get(name)).containsExactly("Скальпель офтальмологический", "Производитель: Mani");

        o.setDetailsInName(false);
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.rows().get(0).cells().get(name)).containsExactly("Скальпель офтальмологический");
    }

    @Test
    void numbersOnlyItemsAndBuildsSectionAndIncludedSpans() {
        ClientOffer o = ClientOfferTestData.newOffer(1);   // колонки NUM, NAME, SUM
        o.getItems().add(ClientOfferTestData.item(o, 1, "Аппарат ИВЛ", "2721000", "5"));
        ClientOfferItem section = ClientOfferTestData.item(o, 2, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(section);
        o.getItems().add(ClientOfferTestData.item(o, 3, "Увлажнитель", "1000", "5"));
        ClientOfferItem included = ClientOfferTestData.item(o, 4, "Гарантийное сервисное обслуживание 37 месяцев", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        o.getItems().add(included);

        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows.get(0).cells().get(0)).containsExactly("1");
        assertThat(rows.get(1).kind()).isEqualTo(KpDocument.RowKind.SECTION);
        assertThat(rows.get(1).spanFrom()).isZero();
        assertThat(rows.get(1).spanLines()).containsExactly("Основные комплектующие:");
        assertThat(rows.get(2).cells().get(0)).containsExactly("2");
        KpDocument.Row inc = rows.get(3);
        assertThat(inc.cells()).hasSize(2);                     // NUM (пусто) + NAME
        assertThat(inc.cells().get(0)).isEmpty();
        assertThat(inc.cells().get(1)).containsExactly("Гарантийное сервисное обслуживание 37 месяцев");
        assertThat(inc.spanFrom()).isEqualTo(2);
        assertThat(inc.spanLines()).containsExactly("Включено в стоимость");
    }

    @Test
    void includedWhenNameIsLastColumnGoesIntoNameCell() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null), new OfferColumn("SUM", null),
                new OfferColumn("NAME", null))));
        ClientOfferItem included = ClientOfferTestData.item(o, 1, "Обучение персонала", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        included.setNote("Включено в стоимость медицинской техники");
        o.getItems().add(included);
        KpDocument.Row row = KpFixtures.document(o, KpFixtures.profileKz()).rows().get(0);
        assertThat(row.spanFrom()).isEqualTo(3);
        assertThat(row.cells().get(2)).containsExactly("Обучение персонала", "Включено в стоимость медицинской техники");
    }

    @Test
    void registrationIsPrintedOnlyWhenConfirmedNotRequiredOrManual() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("REGISTRATION", null))));
        ClientOfferItem manual = ClientOfferTestData.item(o, 1, "А", "1", "5");
        manual.setRegistrationStatus(OfferRegistrationStatus.MANUAL);
        manual.setRegistrationText("№ РК-МИ (МТ)-0№023037");
        ClientOfferItem suggested = ClientOfferTestData.item(o, 2, "Б", "1", "5");
        suggested.setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
        suggested.setRegistrationText("подсказка реестра — не печатать");
        ClientOfferItem notRequired = ClientOfferTestData.item(o, 3, "В", "1", "16");
        notRequired.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
        notRequired.setRegistrationText("Не подлежит регистрации");
        o.getItems().addAll(List.of(manual, suggested, notRequired));
        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows.get(0).cells().get(1)).containsExactly("№ РК-МИ (МТ)-0№023037");
        assertThat(rows.get(1).cells().get(1)).isEmpty();
        assertThat(rows.get(2).cells().get(1)).containsExactly("Не подлежит регистрации");
    }

    @Test
    void totalsVatBreakdownAndWordsFromTheSeptemberOffer() {
        KpDocument d = KpFixtures.document(KpFixtures.offer2409(), KpFixtures.profileKz());
        assertThat(d.totalLines().stream().map(KpDocumentBuilderTest::nb)).containsExactly(
                "Итого: 748 060,00 тг", "в т.ч. НДС 5%: 17 100,00 тг", "в т.ч. НДС 16%: 53 649,65 тг");
        assertThat(d.amountInWords()).isEqualTo("Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын");
        List<String> first = d.rows().get(0).cells().stream().map(c -> nb(String.join("|", c))).toList();
        assertThat(first).containsExactly("1", "Пульсоксиметр QMP – PO70 взрослый", "шт", "3", "105 600,00", "5%",
                "316 800,00", "№ РК-МИ (МТ)-0№023037 от 28.10.2021 г.");
    }

    @Test
    void wordsAndBreakdownCanBeSwitchedOff() {
        ClientOffer o = KpFixtures.offer2409();
        o.setShowAmountInWords(false);
        o.setShowVatBreakdown(false);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.amountInWords()).isNull();
        assertThat(d.totalLines()).hasSize(1);
    }

    @Test
    void termsListIsNumberedWithSemicolonsAndSkipsEmpty() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("", "Цены действительны в течение 10 дней."),
                new OfferTerm("Порядок оплаты", "100% предоплата;"), new OfferTerm("Форма оплаты", " "))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.termsList()).containsExactly("1. Цены действительны в течение 10 дней;", "2. Порядок оплаты: 100% предоплата.");
        assertThat(d.termsTable()).isEmpty();
    }

    @Test
    void termsTableStyle() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTermsStyle(TermsStyle.TABLE);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Условия поставки", "DDP Заказчик"),
                new OfferTerm("Гарантия", "37 месяцев с даты подписания\nакта установки оборудования"))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.termsList()).isEmpty();
        assertThat(d.termsTable()).hasSize(2);
        assertThat(d.termsTable().get(1).valueLines()).containsExactly("37 месяцев с даты подписания", "акта установки оборудования");
    }

    @Test
    void letterheadNumberLineAndRecipient() {
        ClientOffer o = ClientOfferTestData.newOffer(443);
        o.setRecipient("Главному врачу\nГКП на ПХВ «Областная больница»\n");
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.letterhead().left()).containsExactly("Жауапкершілігі", "шектеулі серіктестігі");
        assertThat(d.letterhead().right()).containsExactly("Товарищество", "с ограниченной ответственностью");
        assertThat(d.letterhead().lines()).containsExactly(
                "РНН 271 800 059 535 БИН 121 040 000 303",
                "Республика Казахстан, 090000, Западно-Казахстанская область,",
                "город Уральск, ул.Мухита 121-21",
                "KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)",
                "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA",
                "Тел. 87770752770, электронный адрес: west-med@mail.ru");
        assertThat(d.letterhead().brandText()).isEqualTo("\"West-Med\"");
        assertThat(d.numberLine()).isEqualTo("Исх. № 443 от 14.09.2026 г.");
        assertThat(d.recipientLines()).containsExactly("Главному врачу", "ГКП на ПХВ «Областная больница»");
        assertThat(d.title()).isEqualTo("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ");
    }

    @Test
    void signoffStampOnlyWhenRequestedAndSignatureOnlyForDirector() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(new byte[]{1});
        p.setSignaturePng(new byte[]{2});
        ClientOffer o = ClientOfferTestData.newOffer(1);

        KpDocument.Signoff s = KpFixtures.document(o, p).signoff();
        assertThat(s.director()).isTrue();
        assertThat(s.titleLine()).isEqualTo("Директор ТОО «West-Med»");
        assertThat(s.nameLine()).isEqualTo("Ширяев И. В.");
        assertThat(s.stampPng()).isNull();
        assertThat(s.signaturePng()).isNull();
        assertThat(s.contacts()).isNull();

        o.setWithStamp(true);
        o.setSignoffContacts(true);
        s = KpFixtures.document(o, p).signoff();
        assertThat(s.stampPng()).containsExactly(1);
        assertThat(s.signaturePng()).containsExactly(2);
        assertThat(s.contacts()).isEqualTo("моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru");
        assertThat(s.stampSizeMm()).isEqualTo(40);

        o.setSignoff(OfferSignoff.COMPANY);
        s = KpFixtures.document(o, p).signoff();
        assertThat(s.titleLine()).isEqualTo("ТОО «West-Med»");
        assertThat(s.nameLine()).isNull();
        assertThat(s.signaturePng()).isNull();
        assertThat(s.stampPng()).containsExactly(1);
    }

    // Ниже — правила спеки §6, ломку которых (мутацией сборщика) тесты выше не замечали.

    @Test
    void itemRowsFillEveryColumnAndSectionRowsHaveNoCells() {
        ClientOffer o = KpFixtures.offer2409();   // 8 колонок рынка KZ
        ClientOfferItem section = ClientOfferTestData.item(o, 2, "Расходные материалы", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(1, section);
        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows).extracting(KpDocument.Row::kind).containsExactly(KpDocument.RowKind.ITEM,
                KpDocument.RowKind.SECTION, KpDocument.RowKind.ITEM, KpDocument.RowKind.ITEM, KpDocument.RowKind.ITEM);
        assertThat(rows.get(1).cells()).isEmpty();
        assertThat(rows).filteredOn(r -> r.kind() == KpDocument.RowKind.ITEM).allSatisfy(r -> {
            assertThat(r.cells()).hasSize(8);
            assertThat(r.spanFrom()).isEqualTo(8);
            assertThat(r.spanLines()).isEmpty();
        });
    }

    @Test
    void columnWidthsFollowWeightsAndNameKeepsAQuarterOfACrowdedTable() {
        ClientOffer o = KpFixtures.offer2409();   // колонки КП от 24.09: числам — их вес, наименованию — остаток
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).columns()).extracting(KpDocument.Column::percent)
                .containsExactly(5, 31, 7, 7, 12, 8, 13, 17);

        // + «Страна» (и дубль «Суммы» — не печатается): остальным тесно, 78 > 75 — их доли сжимаются, а наименованию
        // остаётся не меньше четверти (при округлении долей к ближайшему ему доставалось 23 %)
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        o.getTableColumns().add(new OfferColumn("SUM", "Сумма ещё раз"));
        List<KpDocument.Column> columns = KpFixtures.document(o, KpFixtures.profileKz()).columns();
        assertThat(columns).extracting(KpDocument.Column::key)
                .containsExactly("NUM", "NAME", "UNIT", "QTY", "PRICE", "VAT_RATE", "SUM", "REGISTRATION", "COUNTRY");
        assertThat(columns.stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(columns.get(1).percent()).isGreaterThanOrEqualTo(25);

        List<OfferColumn> all = new ArrayList<>();   // все колонки разом: у каждой остаётся место
        for (OfferColumnKey k : OfferColumnKey.values()) all.add(new OfferColumn(k.name(), null));
        o.setTableColumns(all);
        columns = KpFixtures.document(o, KpFixtures.profileKz()).columns();
        assertThat(columns.stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(columns.get(1).percent()).isGreaterThanOrEqualTo(25);
        assertThat(columns).allSatisfy(c -> assertThat(c.percent()).isPositive());
    }

    @Test
    void ownColumnsAndVatColumnsPrintTheirValues() {
        ClientOffer o = KpFixtures.offer2409();
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("MODEL", null),
                new OfferColumn("MANUFACTURER", null), new OfferColumn("COUNTRY", null), new OfferColumn("PRICE_NET", null),
                new OfferColumn("VAT_SUM", null), new OfferColumn("SUM_NET", null), new OfferColumn("NOTE", null))));
        ClientOfferItem first = o.getItems().get(0);    // 3 × 105 600,00, НДС 5% — в т.ч.
        first.setModel("PO70");
        first.setManufacturer("QMP");
        first.setCountry("Китай");
        first.setNote("Со склада в Уральске");
        List<String> cells = KpFixtures.document(o, KpFixtures.profileKz()).rows().get(0).cells().stream()
                .map(c -> nb(String.join("|", c))).toList();
        assertThat(cells).containsExactly("Пульсоксиметр QMP – PO70 взрослый", "PO70", "QMP", "Китай",
                "100 571,43", "15 085,71", "301 714,29", "Со склада в Уральске");

        // у производителя своя колонка, у страны нет — страна второй строкой в наименовании
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("MODEL", null),
                new OfferColumn("MANUFACTURER", null))));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).rows().get(0).cells().get(0))
                .containsExactly("Пульсоксиметр QMP – PO70 взрослый", "Страна: Китай");
    }

    @Test
    void itemWithoutPriceShowsADashNotZero() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("PRICE", null),
                new OfferColumn("VAT_RATE", null), new OfferColumn("SUM", null))));
        o.getItems().add(ClientOfferTestData.item(o, 1, "Кровать функциональная", null, "5"));   // ни закупки, ни ручной цены
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).rows().get(0).cells())
                .containsExactly(List.of("Кровать функциональная"), List.of("—"), List.of("5%"), List.of("—"));
    }

    @Test
    void confirmedRegistrationIsPrintedAndUncheckedIsNot() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("REGISTRATION", null))));
        ClientOfferItem confirmed = ClientOfferTestData.item(o, 1, "А", "1", "5");
        confirmed.setRegistrationStatus(OfferRegistrationStatus.CONFIRMED);
        confirmed.setRegistrationText("№ РК МИ (ИМН)-0 №027522 бессрочно");
        ClientOfferItem unchecked = ClientOfferTestData.item(o, 2, "Б", "1", "5");   // статус по умолчанию — UNCHECKED
        unchecked.setRegistrationText("текст до проверки — не печатать");
        o.getItems().addAll(List.of(confirmed, unchecked));
        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows.get(0).cells().get(1)).containsExactly("№ РК МИ (ИМН)-0 №027522 бессрочно");
        assertThat(rows.get(1).cells().get(1)).isEmpty();
    }

    @Test
    void roubleMarketPrintsRoublesInWordsNotSigns() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setMarket(Market.RF);
        ClientOffer o = ClientOfferTestData.newOffer(1);
        KpFixtures.line(o, "Пульсоксиметр", 1, "1260.00", "22", OfferRegistrationStatus.UNCHECKED, null);
        KpDocument d = KpFixtures.document(o, p);
        assertThat(d.totalLines().stream().map(KpDocumentBuilderTest::nb))
                .containsExactly("Итого: 1 260,00 руб.", "в т.ч. НДС 22%: 227,21 руб.");
        assertThat(d.amountInWords()).isEqualTo("Сумма прописью: Одна тысяча двести шестьдесят рублей 00 копеек");
    }

    @Test
    void termsStyleNoneHidesTermsAndBlankOrMultilineValuesAreTidied() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Гарантия", "12 месяцев"), new OfferTerm("Обучение", " "),
                new OfferTerm("Срок поставки", "30 рабочих дней\nпосле поступления предоплаты"))));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).termsList()).containsExactly(
                "1. Гарантия: 12 месяцев;", "2. Срок поставки: 30 рабочих дней после поступления предоплаты.");

        o.setTermsStyle(TermsStyle.TABLE);
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).termsTable()).extracting(KpDocument.Term::label)
                .containsExactly("Гарантия", "Срок поставки");

        o.setTermsStyle(TermsStyle.NONE);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.termsList()).isEmpty();
        assertThat(d.termsTable()).isEmpty();
    }

    @Test
    void termsListKeepsTheDotOfAnAbbreviation() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTerms(new ArrayList<>(List.of(
                new OfferTerm("Срок действия КП", "до 15.10.2026 г."),
                new OfferTerm("", "Цены действительны в течение 10 дней."),
                new OfferTerm("Сервисный центр", "г. Оренбург."),            // «г» — конец слова «Оренбург», не сокращение
                new OfferTerm("Документы", "счёт-фактура, накладная и т.д."),
                new OfferTerm("Комплектация", "кабели, датчики и т. п."),
                new OfferTerm("", "ЦЕНЫ ДЕЙСТВИТЕЛЬНЫ ДО 31.10.2026 Г."),
                new OfferTerm("Оплата", "до 20.10.2026 г"),                  // точки не было — не дописываем
                new OfferTerm("Гарантия", "12 мес."),
                new OfferTerm("Срок изготовления", "30 раб. дн."),
                new OfferTerm("Срок поставки", "не позднее 31.12.2026 г."))));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).termsList()).containsExactly(
                "1. Срок действия КП: до 15.10.2026 г.;",
                "2. Цены действительны в течение 10 дней;",
                "3. Сервисный центр: г. Оренбург;",
                "4. Документы: счёт-фактура, накладная и т.д.;",
                "5. Комплектация: кабели, датчики и т. п.;",
                "6. ЦЕНЫ ДЕЙСТВИТЕЛЬНЫ ДО 31.10.2026 Г.;",
                "7. Оплата: до 20.10.2026 г;",
                "8. Гарантия: 12 мес.;",
                "9. Срок изготовления: 30 раб. дн.;",
                "10. Срок поставки: не позднее 31.12.2026 г.");
    }

    @Test
    void termLabelTypedWithAColonGetsASingleOne() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Порядок оплаты:", "100% предоплата"),
                new OfferTerm("Форма оплаты :  ", "безналичная"),
                new OfferTerm(" : ", "Цены действительны в течение 10 дней"))));   // от подписи осталось пусто
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).termsList()).containsExactly(
                "1. Порядок оплаты: 100% предоплата;", "2. Форма оплаты: безналичная;",
                "3. Цены действительны в течение 10 дней.");
    }

    @Test
    void blankHeaderTextFallsBackOrIsSkippedAndOrientationIsKept() {
        ClientOffer o = KpFixtures.offer2409();
        o.setTitle(" ");
        o.setSubject(" ");
        o.setLandscape(true);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.title()).isEqualTo("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ");
        assertThat(d.subject()).isNull();
        assertThat(d.intro()).isEqualTo("ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:");
        assertThat(d.recipientLines()).isEmpty();
        assertThat(d.landscape()).isTrue();

        o.setTitle(" Ценовое предложение ");
        o.setSubject("на поставку медицинских изделий");
        o.setIntro("\n");
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.title()).isEqualTo("Ценовое предложение");
        assertThat(d.subject()).isEqualTo("на поставку медицинских изделий");
        assertThat(d.intro()).isNull();
    }

    @Test
    void letterheadSkipsEmptyRequisitesAndCarriesTheLogo() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setHeaderLeft(null);
        p.setHeaderRight(" \n ");
        p.setLogoPng(new byte[]{7});
        p.setBrandText(" ");
        p.setIdsLine(" ");
        p.setAddress("  г. Уральск,\r\n\r\n  ул. Мухита 121-21  ");
        p.setAccounts(null);
        p.setBankName(" ");
        p.setEmail(null);
        KpDocument.Letterhead lh = KpFixtures.document(ClientOfferTestData.newOffer(1), p).letterhead();
        assertThat(lh.left()).isEmpty();
        assertThat(lh.right()).isEmpty();
        assertThat(lh.logoPng()).containsExactly(7);
        assertThat(lh.brandText()).isNull();
        assertThat(lh.lines()).containsExactly("г. Уральск,", "ул. Мухита 121-21", "БИК: TSESKZKA", "Тел. 87770752770");
    }

    @Test
    void signoffWithoutDirectorTitleUsesCompanyNameAndKeepsStampSize() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setDirectorTitle(" ");
        p.setDirectorName("  Иванов   Пётр  ");
        p.setSignoffContacts(" ");
        p.setStampSizeMm(45);
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setSignoffContacts(true);
        KpDocument.Signoff s = KpFixtures.document(o, p).signoff();
        assertThat(s.titleLine()).isEqualTo("ТОО «West-Med»");
        assertThat(s.nameLine()).isEqualTo("Иванов П.");
        assertThat(s.contacts()).isNull();
        assertThat(s.stampSizeMm()).isEqualTo(45);
    }
}
