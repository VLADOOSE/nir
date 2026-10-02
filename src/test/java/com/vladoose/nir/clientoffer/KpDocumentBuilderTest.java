package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.offer.ColumnAlign;
import com.vladoose.nir.service.offer.OfferColumnKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    /** Колонки денег — под самое длинное число, если оно не влезает в обычную долю; альбомный лист шире — хватает её. */
    @Test
    void moneyColumnsWidenForMillionsOnlyWhenTheyDoNotFit() {
        assertThat(KpFixtures.document(KpFixtures.offer2409(), KpFixtures.profileKz()).tableFontPt()).isEqualTo(10.0);
        ClientOffer o = KpFixtures.withMillionPrices(KpFixtures.offer2409());
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.columns()).extracting(KpDocument.Column::percent)
                .containsExactly(5, 29, 7, 7, 13, 8, 14, 17);   // цена 12 → 13 («7 650 000,00»), сумма 13 → 14 («15 300 000,00»)
        assertThat(d.tableFontPt()).isEqualTo(10.0);
        o.setLandscape(true);
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.columns()).extracting(KpDocument.Column::percent).containsExactly(5, 31, 7, 7, 12, 8, 13, 17);
        assertThat(d.tableFontPt()).isEqualTo(10.0);
    }

    /**
     * Не влезает в 75 % — таблица мельчает шагом 0,5 pt, а не сжимает числа: веса по умолчанию уменьшаются вместе с
     * кеглем, ширина чисел считается заново. «Страна» — 9,5 pt; «Страна» и миллионы — 9 pt; «Страна», «Производитель» и
     * миллионы не влезают и в 8 pt — деньги держат свою ширину (цена 11 %, сумма 12 %), сжимаются остальные колонки.
     */
    @Test
    void crowdedTablesShrinkTheTypeInsteadOfSqueezingNumbers() {
        ClientOffer o = KpFixtures.offer2409();
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).tableFontPt()).isEqualTo(9.5);
        KpFixtures.withMillionPrices(o);
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).tableFontPt()).isEqualTo(9.0);
        o.getTableColumns().add(new OfferColumn("MANUFACTURER", null));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.tableFontPt()).isEqualTo(8.0);
        assertThat(d.columns()).filteredOn(c -> c.key().equals("PRICE")).extracting(KpDocument.Column::percent).containsExactly(11);
        assertThat(d.columns()).filteredOn(c -> c.key().equals("SUM")).extracting(KpDocument.Column::percent).containsExactly(12);
        assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(d.columns().get(1).percent()).isGreaterThanOrEqualTo(25);
    }

    /**
     * Теснота — в миллиметрах: колонки, которым тесно на книжном листе (178 мм), на альбомном (267 мм) помещаются — там
     * обычный кегль. Доли по умолчанию на альбомном листе бывают больше 75 % — первыми уступают деньги и короткие излишком
     * своей доли сверх нужды, ровно сколько нужно (цена: с «Страной» — своя доля 12 %, с колонками без НДС — 9 %), затем
     * текст; до нужды (на альбомном листе цене хватает 8 %) — только когда без этого не обойтись.
     */
    @Test
    void landscapeMeasuresCrowdingInMillimetres() {
        String[][] extras = {{"COUNTRY"}, {"PRICE_NET", "VAT_SUM", "SUM_NET"}, {"MODEL", "MANUFACTURER", "COUNTRY"}};
        double[] portraitPt = {9.5, 8.0, 8.0};
        int[] landscapePrice = {12, 9, 8};
        for (int i = 0; i < extras.length; i++) {
            ClientOffer o = KpFixtures.offer2409();
            for (String k : extras[i]) o.getTableColumns().add(new OfferColumn(k, null));
            assertThat(KpFixtures.document(o, KpFixtures.profileKz()).tableFontPt()).as("книжная +%s", (Object) extras[i])
                    .isEqualTo(portraitPt[i]);
            o.setLandscape(true);
            KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
            assertThat(d.tableFontPt()).as("альбомная +%s", (Object) extras[i]).isEqualTo(10.0);
            assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
            assertThat(d.columns()).filteredOn(c -> c.key().equals("PRICE")).extracting(KpDocument.Column::percent)
                    .as("альбомная +%s", (Object) extras[i]).containsExactly(landscapePrice[i]);
            assertThat(d.columns().get(1).percent()).isGreaterThanOrEqualTo(25);
        }
    }

    /**
     * Альбомный лист и «Страна»: доли по умолчанию — 78 % (наименованию осталось бы 22 %), а в миллиметрах тесноты нет —
     * обычный кегль, и цена с суммой держат свою ширину (12 и 13 %, во втором раунде — 11 и 12 % при 9,5 pt), а не
     * срезаются до нужды (8 и 8 %, пока наименованию доставался 31 %): уступают излишек короткие колонки и текст.
     */
    @Test
    void landscapeWithCountryKeepsPriceAndSumAtTheirShare() {
        ClientOffer o = KpFixtures.offer2409();
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        o.setLandscape(true);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.tableFontPt()).isEqualTo(10.0);
        assertThat(d.columns()).extracting(KpDocument.Column::key, KpDocument.Column::percent).contains(
                tuple("PRICE", 12), tuple("SUM", 13));
        assertThat(d.columns().get(1).percent()).isEqualTo(25);
        assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
    }

    /**
     * Классы колонок — по ключу: деньги и короткие (№, кол-во, ед. изм., ставка) не переносятся, текст (модель,
     * производитель, страна, регистрация, примечание) переносится всегда, даже если в каждой ячейке одно «слово» (код
     * модели, «Германия»). Короткая колонка со свободным текстом длиннее своей доли («упаковка» в ед. изм. на книжном листе:
     * доля 7 % = 12,5 мм, слово ≈ 17 мм) в этом документе — текст; на альбомном листе (7 % = 18,7 мм) «упаковка» влезает —
     * короткая.
     */
    @Test
    void columnClassesFollowTheKeyAndAShortColumnWithLongTextWraps() {
        ClientOffer o = KpFixtures.offer2409();
        ClientOfferItem gloves = KpFixtures.line(o, "Перчатки смотровые", 1000, "15.00", "5", OfferRegistrationStatus.UNCHECKED, null);
        for (ClientOfferItem it : o.getItems()) {
            it.setModel("ABCDEFGHIJKLMNOPQRSTUV");
            it.setManufacturer("Mindray");
            it.setCountry("Германия");
            it.setNote("Новинка");
        }
        for (String k : new String[] {"MODEL", "MANUFACTURER", "COUNTRY", "NOTE"}) o.getTableColumns().add(new OfferColumn(k, null));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.columns()).filteredOn(KpDocument.Column::nowrap).extracting(KpDocument.Column::key)
                .containsExactly("NUM", "UNIT", "QTY", "PRICE", "VAT_RATE", "SUM");

        gloves.setUnit("упаковка");
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).columns()).filteredOn(KpDocument.Column::nowrap)
                .extracting(KpDocument.Column::key).containsExactly("NUM", "QTY", "PRICE", "VAT_RATE", "SUM");
        o.setLandscape(true);
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).columns()).filteredOn(KpDocument.Column::nowrap)
                .extracting(KpDocument.Column::key).containsExactly("NUM", "UNIT", "QTY", "PRICE", "VAT_RATE", "SUM");
    }

    /**
     * Таблицы на 12 строк, которым хватает шагов 1–3 подбора (кегль; сжатие текста; наименование до 20 %): «№» и «Кол-во»
     * не уже полей ячейки и одной буквы (1 em) — номера 10–12 не рвутся. Раньше запасной путь сжимал их до 3 % = 5,3 мм.
     */
    @Test
    void rowNumberAndQuantityKeepPaddingAndAGlyphInTwelveRowTables() {
        String[][] sets = {{"MODEL", "MANUFACTURER", "COUNTRY"}, {"PRICE_NET", "VAT_SUM", "SUM_NET"},
                {"PRICE_NET", "VAT_SUM", "SUM_NET", "COUNTRY"}, {"MODEL", "MANUFACTURER", "COUNTRY", "NOTE"}};
        for (boolean landscape : new boolean[] {false, true}) {
            for (String[] extras : sets) {
                ClientOffer o = KpFixtures.offer2409();
                for (int i = 0; i < 8; i++) KpFixtures.line(o, "Доп. позиция " + i, 1, "1000.00", "5", OfferRegistrationStatus.UNCHECKED, null);
                for (String k : extras) o.getTableColumns().add(new OfferColumn(k, null));
                o.setLandscape(landscape);
                KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
                double page = landscape ? 267 : 178, minMm = 2 * 1.5 + d.tableFontPt() * 25.4 / 72;
                for (String key : new String[] {"NUM", "QTY"}) {
                    assertThat(d.columns()).filteredOn(c -> c.key().equals(key)).singleElement()
                            .satisfies(c -> assertThat(c.percent() * page / 100).as("%s %s +%s", landscape ? "альбомная" : "книжная",
                                    key, String.join("+", extras)).isGreaterThanOrEqualTo(minMm));
                }
            }
        }
    }

    /**
     * Все 15 колонок на книжном листе (обычные цены) — единственный из наборов КП, которому не хватает и шага 3: деньги по
     * нужде (68 %) и текст по пределу (5 колонок × 5 %) не оставляют наименованию и 20 %. Это шаг 4 — прежнее
     * пропорциональное сжатие: наименованию четверть, цене 6 % = 10,7 мм, хотя «105 600,00» в 8 pt — 12,7 мм с полями 15,7 мм
     * (числа выходят за ячейки; редактор предложит альбомный лист). Те же колонки на альбомном листе влезают в шаг 2.
     */
    @Test
    void fifteenColumnsOnPortraitAreLeftToTheProportionalSqueeze() {
        ClientOffer o = KpFixtures.offer2409();
        List<OfferColumn> all = new ArrayList<>();
        for (OfferColumnKey k : OfferColumnKey.values()) all.add(new OfferColumn(k.name(), null));
        o.setTableColumns(all);
        double priceMm = 4.5 * 8 * 25.4 / 72 + 2 * 1.5;   // «105 600,00»: 8 цифр по 0,5 em, пробел и запятая по 0,25 em
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.tableFontPt()).isEqualTo(8.0);
        assertThat(d.columns().get(1).percent()).isEqualTo(25);
        assertThat(d.columns()).filteredOn(c -> c.key().equals("PRICE")).singleElement()
                .satisfies(c -> assertThat(c.percent() * 178.0 / 100).isLessThan(priceMm));
        o.setLandscape(true);
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.tableFontPt()).isEqualTo(8.0);
        assertThat(d.columns()).filteredOn(c -> c.key().equals("PRICE")).singleElement()
                .satisfies(c -> assertThat(c.percent() * 267.0 / 100).isGreaterThanOrEqualTo(priceMm));
    }

    /**
     * Проценты: деньгам и коротким — округлением вверх (их ширина не урезается и на долю процента), остальным —
     * наибольшими остатками, а не вниз: «Страна» на книжном листе (9,5 pt, веса × 0,95: номер 4,75, ед. изм. и кол-во
     * 6,65, цена 11,4, ставка 7,6, сумма 12,35) — 5/7/7/12/8/13, а регистрация, страна и наименование делят остальное
     * (было при округлении вниз 4/30/6/6, при наибольших остатках для всех — цена 11, сумма 12). Недостача округления —
     * наибольшему остатку, а не наименованию: «Страна», «Производитель» и миллионы (8 pt, регистрация 13,6, страна 7,2,
     * производитель 12, наименование 25,2) — пункт получает регистрация.
     */
    @Test
    void percentsUseLargestRemainders() {
        ClientOffer o = KpFixtures.offer2409();
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).columns()).extracting(KpDocument.Column::percent)
                .containsExactly(5, 25, 7, 7, 12, 8, 13, 15, 8);
        KpFixtures.withMillionPrices(o);
        o.getTableColumns().add(new OfferColumn("MANUFACTURER", null));
        assertThat(KpFixtures.document(o, KpFixtures.profileKz()).columns()).extracting(KpDocument.Column::percent)
                .containsExactly(4, 25, 5, 4, 11, 6, 12, 14, 7, 12);
    }

    /**
     * Абсурдно тесная таблица (все 15 колонок на книжном листе и цена на сотни миллионов): деньгам не уступить и в 8 pt
     * (остальным колонкам не осталось бы и по 1 %) — прежнее пропорциональное сжатие всех колонок; наименованию —
     * по-прежнему не меньше четверти, у каждой колонки — своя доля.
     */
    @Test
    void absurdlyCrowdedTableFallsBackToTheProportionalSqueeze() {
        ClientOffer o = KpFixtures.offer2409();
        KpFixtures.line(o, "Томограф магнитно-резонансный", 2, "300000000.00", "5", OfferRegistrationStatus.UNCHECKED, null);
        List<OfferColumn> all = new ArrayList<>();
        for (OfferColumnKey k : OfferColumnKey.values()) all.add(new OfferColumn(k.name(), null));
        o.setTableColumns(all);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.tableFontPt()).isEqualTo(8.0);
        assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(d.columns().get(1).percent()).isGreaterThanOrEqualTo(25);
        assertThat(d.columns()).allSatisfy(c -> assertThat(c.percent()).isPositive());
    }

    /** Тесная таблица с миллионами: колонки денег шире, но наименованию — всё равно не меньше четверти. */
    @Test
    void crowdedTableWithMillionsStillKeepsNameAQuarter() {
        ClientOffer o = KpFixtures.withMillionPrices(KpFixtures.offer2409());
        List<OfferColumn> all = new ArrayList<>();
        for (OfferColumnKey k : OfferColumnKey.values()) all.add(new OfferColumn(k.name(), null));
        o.setTableColumns(all);
        List<KpDocument.Column> columns = KpFixtures.document(o, KpFixtures.profileKz()).columns();
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
