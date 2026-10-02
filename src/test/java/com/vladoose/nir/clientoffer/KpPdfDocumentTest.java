package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** PDF КП целиком (спека §6): бланк, таблица, итоги, пропись, условия, подпись; экранирование; печать; ориентация. */
class KpPdfDocumentTest {

    /** Поле ячейки таблицы позиций по горизонтали (offer.html: .items td { padding: 1.2mm 1.5mm }), мм. */
    private static final float PAD = 1.5f;
    private static final Offset<Float> MM = within(0.5f);

    /** Края области текста листа, мм: столбцы шапки бланка прижаты к ним (таблица во всю ширину). */
    private record Edges(float left, float right) {
        static Edges of(byte[] pdf) {
            return new Edges(KpTestSupport.find(pdf, "Жауапкершілігі").left(),
                    KpTestSupport.find(pdf, "с ограниченной ответственностью").right());
        }

        /** Левый и правый край колонок [from, to] таблицы позиций — по долям из модели документа. */
        float[] columns(List<KpDocument.Column> cols, String from, String to) {
            float width = right - left, before = 0, at = 0, end = 0;
            for (KpDocument.Column c : cols) {
                if (c.key().equals(from)) at = before;
                before += c.percent();
                if (c.key().equals(to)) end = before;
            }
            return new float[] {left + width * at / 100, left + width * end / 100};
        }
    }

    @Test
    void fullDocumentLooksLikeTheSeptemberOffer() {
        String text = KpTestSupport.text(KpFixtures.pdf(KpFixtures.offer2409(), KpFixtures.profileKz()));
        assertThat(text).contains(
                "Жауапкершілігі", "Товарищество", "\"West-Med\"",
                "РНН 271 800 059 535 БИН 121 040 000 303",
                "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA",
                "Тел. 87770752770, электронный адрес: west-med@mail.ru",
                "Исх. № 443 от 14.09.2026 г.", "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ",
                "ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:",
                "Цена за ед., тг", "Регистрация в РК",
                "Пульсоксиметр QMP – PO70 взрослый", "105 600,00", "316 800,00", "Не подлежит регистрации",
                "Итого: 748 060,00 тг", "в т.ч. НДС 5%: 17 100,00 тг", "в т.ч. НДС 16%: 53 649,65 тг",
                "Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
                "1. Цены действительны в течение 10 дней;",
                "5. Срок поставки всего товара: 30 рабочих дней после поступления предоплаты.",
                "С уважением,", "Директор ТОО «West-Med»", "Ширяев И. В.");
    }

    /** Текст оператора — только текст: разметка не исполняется и никуда не ходит (спека §6.4, §11). */
    @Test
    void operatorTextIsEscapedAndNothingIsFetched() throws Exception {
        try (KpTestSupport.TrapServer trap = new KpTestSupport.TrapServer()) {
            ClientOffer o = ClientOfferTestData.newOffer(1);
            o.getItems().add(ClientOfferTestData.item(o, 1,
                    "<b>Жирный</b> & <img src=\"" + trap.url("/evil.png") + "\"/>", "1000", "5"));
            o.setSubject("<script>alert(1)</script>");
            String text = KpTestSupport.text(KpFixtures.pdf(o, KpFixtures.profileKz()));
            assertThat(text).contains("<b>Жирный</b> &", "<script>alert(1)</script>");
            assertThat(trap.hits()).isZero();
        }
    }

    @Test
    void stampAndSignatureOnlyWithCheckbox() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        ClientOffer o = KpFixtures.offer2409();
        assertThat(KpTestSupport.imageCount(KpFixtures.pdf(o, p))).isZero();
        o.setWithStamp(true);
        assertThat(KpTestSupport.imageCount(KpFixtures.pdf(o, p))).isEqualTo(2);
    }

    @Test
    void logoReplacesBrandText() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setLogoPng(KpTestSupport.circlePng());
        byte[] pdf = KpFixtures.pdf(KpFixtures.offer2409(), p);
        assertThat(KpTestSupport.imageCount(pdf)).isEqualTo(1);
        assertThat(KpTestSupport.text(pdf)).doesNotContain("\"West-Med\"");
    }

    @Test
    void letterheadOnlyOnFirstPageAndHeaderRepeats() {
        ClientOffer o = KpFixtures.offer2409();
        for (int i = 0; i < 70; i++) {
            KpFixtures.line(o, "Позиция длинного КП № " + i, 1, "1000.00", "5", OfferRegistrationStatus.UNCHECKED, null);
        }
        List<String> pages = KpTestSupport.pageTexts(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages.get(0)).contains("РНН 271 800 059 535");
        assertThat(pages.get(1)).doesNotContain("РНН 271 800 059 535").contains("Цена за ед., тг");
    }

    @Test
    void landscapePageIsWide() {
        ClientOffer o = KpFixtures.offer2409();
        o.setLandscape(true);
        PDRectangle size = KpTestSupport.firstPageSize(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(size.getWidth()).isGreaterThan(size.getHeight());
    }

    @Test
    void termsAsTableAndRenamedColumn() {
        ClientOffer o = KpFixtures.offer2409();
        o.setTermsStyle(TermsStyle.TABLE);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Условия поставки", "DDP Заказчик"))));
        o.getTableColumns().get(1).setLabel("Наименование медицинской техники (по регистрационному удостоверению)");
        String text = KpTestSupport.text(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(text).contains("Условия поставки", "DDP Заказчик", "по регистрационному удостоверению")
                .doesNotContain("1. Условия поставки");
    }

    /**
     * Предмет и вводная — как их набрал оператор: каждая строка — своей строкой документа (white-space: pre-line).
     * Строки короткие: склеенные в одну, они уместились бы в ширину листа — без pre-line тест падает.
     */
    @Test
    void subjectAndIntroKeepOperatorLineBreaks() {
        ClientOffer o = KpFixtures.offer2409();
        o.setSubject("Поставка пульсоксиметров\nдля приёмного отделения");
        o.setIntro("Уважаемый Иван Петрович!\nПредлагаем поставку по следующим ценам:");
        List<String> lines = KpTestSupport.lines(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(lines).containsSequence(
                "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ",
                "Поставка пульсоксиметров", "для приёмного отделения",
                "Уважаемый Иван Петрович!", "Предлагаем поставку по следующим ценам:");
    }

    /** Строки бланка и заголовок — по центру жирным (спека §6.3, §6.1 п.3). */
    @Test
    void letterheadLinesAndTitleAreBoldAndCentered() {
        byte[] pdf = KpFixtures.pdf(KpFixtures.offer2409(), KpFixtures.profileKz());
        Edges page = Edges.of(pdf);
        for (String s : List.of("РНН 271 800 059 535 БИН 121 040 000 303", "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA",
                "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ")) {
            KpTestSupport.Placed line = KpTestSupport.find(pdf, s);
            assertThat(line.font()).as(s).contains("Bold");
            assertThat((line.left() + line.right()) / 2).as(s).isCloseTo((page.left() + page.right()) / 2, MM);
        }
    }

    /** «Кому» — справа, первой строкой напротив «Исх. №» (спека §6.1 п.2). */
    @Test
    void recipientStandsRightOfTheNumberLine() {
        ClientOffer o = KpFixtures.offer2409();
        o.setRecipient("Главному врачу\nГКП «Областная больница»");
        byte[] pdf = KpFixtures.pdf(o, KpFixtures.profileKz());
        Edges page = Edges.of(pdf);
        KpTestSupport.Placed first = KpTestSupport.find(pdf, "Главному врачу");
        assertThat(first.right()).isCloseTo(page.right(), MM);
        assertThat(first.top()).isCloseTo(KpTestSupport.find(pdf, "Исх. № 443").top(), MM);
        assertThat(KpTestSupport.find(pdf, "ГКП «Областная больница»").right()).isCloseTo(page.right(), MM);
    }

    /** Колонки — своей долей ширины (KpDocument.Column.percent); наименование слева, суммы справа, ставка по центру. */
    @Test
    void columnsTakeTheirShareOfTheWidthAndAlign() {
        ClientOffer o = KpFixtures.offer2409();
        List<KpDocument.Column> cols = KpFixtures.document(o, KpFixtures.profileKz()).columns();
        byte[] pdf = KpFixtures.pdf(o, KpFixtures.profileKz());
        Edges page = Edges.of(pdf);
        assertThat(KpTestSupport.find(pdf, "Пульсоксиметр").left()).isCloseTo(page.columns(cols, "NAME", "NAME")[0] + PAD, MM);
        assertThat(KpTestSupport.find(pdf, "316 800,00").right()).isCloseTo(page.columns(cols, "SUM", "SUM")[1] - PAD, MM);
        KpTestSupport.Placed rate = KpTestSupport.find(pdf, "16%");
        float[] vat = page.columns(cols, "VAT_RATE", "VAT_RATE");
        assertThat((rate.left() + rate.right()) / 2).isCloseTo((vat[0] + vat[1]) / 2, MM);
    }

    /** Длинный код модели без пробелов переносится внутри ячейки наименования, а не залезает в соседнюю (спека §6.4). */
    @Test
    void longModelCodeWrapsInsideItsCell() {
        ClientOffer o = KpFixtures.offer2409();
        String code = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJ";
        KpFixtures.line(o, "Датчик " + code, 1, "1000.00", "5", OfferRegistrationStatus.UNCHECKED, null);
        List<KpDocument.Column> cols = KpFixtures.document(o, KpFixtures.profileKz()).columns();
        byte[] pdf = KpFixtures.pdf(o, KpFixtures.profileKz());
        float nameRight = Edges.of(pdf).columns(cols, "NAME", "NAME")[1];
        assertThat(KpTestSupport.find(pdf, code).right()).isLessThanOrEqualTo(nameRight - PAD + 0.5f);
    }

    /** Раздел — на всю ширину, жирным, слева; «включено в стоимость» — по центру объединённой ячейки (спека §6.1 п.5). */
    @Test
    void sectionAndIncludedRowsSpanTheTable() {
        ClientOffer o = KpFixtures.offer2409();
        ClientOfferItem section = ClientOfferTestData.item(o, 5, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(section);
        ClientOfferItem included = ClientOfferTestData.item(o, 6, "Гарантийное обслуживание", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        included.setNote("Включено в стоимость медицинской техники");
        o.getItems().add(included);
        List<KpDocument.Column> cols = KpFixtures.document(o, KpFixtures.profileKz()).columns();
        byte[] pdf = KpFixtures.pdf(o, KpFixtures.profileKz());
        Edges page = Edges.of(pdf);

        // без объединения обе фразы разломало бы по узкой колонке на куски
        assertThat(KpTestSupport.lines(pdf)).contains("Основные комплектующие:")
                .anySatisfy(l -> assertThat(l).contains("Включено в стоимость медицинской техники"));
        KpTestSupport.Placed sectionText = KpTestSupport.find(pdf, "Основные комплектующие:");
        assertThat(sectionText.font()).contains("Bold");
        assertThat(sectionText.left()).isCloseTo(page.left() + PAD, MM);
        KpTestSupport.Placed note = KpTestSupport.find(pdf, "Включено в стоимость медицинской техники");
        float[] merged = page.columns(cols, "UNIT", "REGISTRATION");   // после наименования — до конца строки
        assertThat((note.left() + note.right()) / 2).isCloseTo((merged[0] + merged[1]) / 2, MM);
    }

    /** «Итого» — справа жирным, разбивка НДС — обычным (спека §6.1 п.6). */
    @Test
    void totalIsBoldOnTheRight() {
        byte[] pdf = KpFixtures.pdf(KpFixtures.offer2409(), KpFixtures.profileKz());
        KpTestSupport.Placed total = KpTestSupport.find(pdf, "Итого: 748 060,00 тг");
        assertThat(total.right()).isCloseTo(Edges.of(pdf).right(), MM);
        assertThat(total.font()).contains("Bold");
        assertThat(KpTestSupport.find(pdf, "в т.ч. НДС 5%: 17 100,00 тг").font()).doesNotContain("Bold");
    }

    /** Подпись от компании — без должности и линии; контакты — строкой ниже (спека §6.3). */
    @Test
    void companySignoffWithContacts() {
        ClientOffer o = KpFixtures.offer2409();
        o.setSignoff(OfferSignoff.COMPANY);
        o.setSignoffContacts(true);
        byte[] pdf = KpFixtures.pdf(o, KpFixtures.profileKz());
        assertThat(KpTestSupport.lines(pdf)).containsSequence(
                "С уважением,", "ТОО «West-Med»", "моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru");
        assertThat(KpTestSupport.text(pdf)).doesNotContain("Директор", "Ширяев");
    }

    /** Подпись — на линии подписи, правее должности, не выше 15 мм и не шире линии (спека §6.3). */
    @Test
    void signatureSitsOnTheSignLine() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setSignaturePng(KpTestSupport.signaturePng());
        ClientOffer o = KpFixtures.offer2409();
        o.setWithStamp(true);   // печати в реквизитах нет — рисуется одна подпись
        byte[] pdf = KpFixtures.pdf(o, p);
        List<KpTestSupport.Box> images = KpTestSupport.imageBoxes(pdf);
        assertThat(images).hasSize(1);
        KpTestSupport.Box signature = images.get(0);
        KpTestSupport.Placed title = KpTestSupport.find(pdf, "Директор ТОО «West-Med»");
        assertThat(signature.height()).isLessThanOrEqualTo(15.5f);
        assertThat(signature.width()).isLessThanOrEqualTo(44.5f);
        assertThat(signature.left()).isGreaterThanOrEqualTo(title.right());
        assertThat(signature.bottom()).isCloseTo(title.bottom(), within(1.5f));   // низ подписи — у строки должности
    }
}
