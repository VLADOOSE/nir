package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LotDescriptiveTextTest {

    /** Реальный goszakup-ТЗ: метки часто разорваны переносами PDF-извлечения (॰«Дополнительное\nописание лота:»). */
    private static final String GOSZAKUP_TZ = String.join("\n",
            "Приложение 2",
            "к конкурсной документации",
            "Номер закупки: № 17279420-1",
            "Наименование",
            "закупки:",
            "Резиновые пластинки для аппарата электрофореза \"Элэскулап\"",
            "Номер лота: № 86887302-ЗЦП3",
            "Наименование лота: Электрод",
            "Описание лота: платиновый",
            "Дополнительное",
            "описание лота:",
            "электроды силиконовые 55*80 мм",
            "Количество: 10",
            "Единица измерения: Комплект",
            "Места поставки: 231010000, Атырауская область, г.Атырау",
            "Срок поставки: в течение 2026 года (по заявке Заказчика)");

    @Test
    void goszakupTz_keepsDescriptive_dropsProcurementNoise() {
        String t = LotDescriptiveText.forMatching("Электрод", null, GOSZAKUP_TZ);
        // описательное — из «Дополнительное описание лота» (метка была разорвана переносом) сохранено
        assertThat(t.toLowerCase()).contains("силиконовые").contains("55").contains("80").contains("электрод");
        // закупочный канцелярит и адрес/номера — выброшены (иначе раздувают знаменатель score)
        assertThat(t).doesNotContain("17279420").doesNotContain("231010000");
        assertThat(t.toLowerCase()).doesNotContain("атырауская").doesNotContain("поставки");
    }

    @Test
    void requirementsForEmail_keepsTechDropsBoilerplate_noNameInjected() {
        String t = LotDescriptiveText.requirementsForEmail(GOSZAKUP_TZ);
        assertThat(t.toLowerCase()).contains("силиконовые").contains("55").contains("80"); // тех.суть
        assertThat(t).doesNotContain("17279420").doesNotContain("231010000");              // номер/адрес — вон
        assertThat(t.toLowerCase()).doesNotContain("поставки");
    }

    @Test
    void requirementsForEmail_blankOrNull_returnsEmpty() {
        assertThat(LotDescriptiveText.requirementsForEmail(null)).isEmpty();
        assertThat(LotDescriptiveText.requirementsForEmail("   ")).isEmpty();
    }

    @Test
    void specWithoutLabels_fallsBackToFullText() {
        // ручной/иной формат ТЗ без goszakup-меток → берём как есть (без регресса)
        String t = LotDescriptiveText.forMatching("Электрод", "Bar", "силиконовые электроды 55х80");
        assertThat(t).contains("Электрод").contains("Bar").contains("силиконовые электроды 55х80");
    }

    @Test
    void blankSpec_returnsNameAndManufactOnly() {
        assertThat(LotDescriptiveText.forMatching("Электрод", "Мед ТеКо", null)).isEqualTo("Электрод Мед ТеКо");
        assertThat(LotDescriptiveText.forMatching("Электрод", null, "  ")).isEqualTo("Электрод");
    }

    // --- СК-Фармация: анти-лик кода лота площадки -------------------------------------------------
    // Фикстуры — РЕАЛЬНЫЕ ТЗ из nirdb (не синтетика), по одной на каждую встреченную форму шапки.
    // Код лота вида «4875083-Т1» адресует объявление fms.ecc.kz/ru/announce/index/<id>, поэтому
    // в письмо поставщику он попадать не должен (анти-лик §9): раскрывает конкретный тендер.

    /** Любой остаток кода лота площадки — 6–9 цифр + «-Т<цифра>», с любыми пробелами вокруг дефиса. */
    private static final Pattern PLATFORM_CODE = Pattern.compile("(?<!\\d)\\d{6,9}\\s*-\\s*[ТT]\\d");

    private static String realSpec(String fixture) throws IOException {
        try (InputStream in = LotDescriptiveTextTest.class
                .getResourceAsStream("/skpharmacy/specs/" + fixture + ".txt")) {
            assertThat(in).as("фикстура %s", fixture).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Шапка СК-Фармации варьируется — «Лот 4875083-Т1» / «Лот № 4872223-Т1» / «Лот  4875003 -Т1»
     * (пробелы вокруг дефиса) / вовсе без строки лота / без строки «№ п/п». Гарантия одна на все формы:
     * ни номера лота площадки, ни его цифр в исходящем тексте нет, а тех. содержание остаётся.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // фикстура,          цифры кода лота, номер объявления, содержательный маркер требований
            "lot-4875083-t1,      4875083,         522144,           FibroScan",   // «Лот 4875083-Т1», без №
            "lot-4872223-t1,      4872223,         521064,           МЕДИКС",      // «Лот № 4872223-Т1»
            "lot-4875003-t1,      4875003,         522064,           uCT",         // «Лот  4875003 -Т1», пробелы у дефиса
            "lot-4854483-t1,      4854483,         519424,           SIGNA",       // без строки «№ п/п»
            "lot-1040829-t1,      1040829,         521964,           ходьбы",      // «Техническая спецификация*», без строки лота
            "lot-1040409-t1,      1040409,         521464,           Гентри",      // без строки лота
    })
    void skSpec_emailTextCarriesNoPlatformIdentifier(String fixture, String lotCodeDigits,
                                                     String announceId, String contentMarker) throws IOException {
        String spec = realSpec(fixture);
        String email = LotDescriptiveText.requirementsForEmail(spec);

        // прямая гарантия: ни цифр кода лота, ни номера объявления, ни самой формы кода
        assertThat(email).doesNotContain(lotCodeDigits).doesNotContain(announceId);
        assertThat(PLATFORM_CODE.matcher(email).find())
                .as("остаток кода лота площадки в тексте письма: %s", fixture).isFalse();
        // и шапка документа тоже ушла — текст начинается с требований, а не с «Техническая спецификация»
        assertThat(email).doesNotStartWith("Техническая спецификация");
        // при этом требования сохранены — санитайзер не выжег содержание
        assertThat(email).contains(contentMarker);
        assertThat(email.length()).isGreaterThan(1000);
    }

    /** То же ТЗ по пути матчинга: шапка не должна раздувать знаменатель score, имя/бренд на месте. */
    @Test
    void skSpec_matchingTextDropsHeaderKeepsNameAndBrand() throws IOException {
        String t = LotDescriptiveText.forMatching("Аппарат УЗИ", "Samsung", realSpec("lot-4875083-t1"));
        assertThat(t).startsWith("Аппарат УЗИ Samsung ").contains("FibroScan").doesNotContain("4875083");
        assertThat(t).doesNotContain("Критерии Описание"); // заголовок таблицы — тоже шум для матчинга
    }

    /**
     * Второй рубеж независим от шапки: код лота вычищается и там, где узнаваемой шапки нет вовсе
     * (иная вёрстка PDF, ручной ТЗ) — гарантия не держится на одном якоре.
     */
    @Test
    void platformCodeScrubbed_evenWithoutRecognisableHeader() {
        String t = LotDescriptiveText.requirementsForEmail("Позиционер для ангиографии, лот 4809231-Т1, 1 шт.");
        assertThat(t).doesNotContain("4809231").contains("Позиционер").contains("ангиографии");
    }

    // --- СК-Фармация: анти-лик заказчика (коммерческий хвост) --------------------------------------
    // Ниже требований таблица ТЗ продолжается условиями поставки и несёт ИМЯ И АДРЕС ЗАКАЗЧИКА.
    // Заказчик раскрывает тендер прямее кода лота: зная больницу и предмет, поставщик находит
    // объявление сам. Проверяем ПОЛНЫЙ санитизированный текст, а не обрезанный до SPEC_LIMIT, —
    // гарантия должна держаться при любом лимите, иначе она снова станет свойством данных.

    /** Маркеры коммерческого блока: место назначения поставки и организационная форма заказчика. */
    private static final Pattern DELIVERY_MARKER = Pattern.compile(
            "ИНКОТЕРМС|DDP\\s+пункт|пункт[а-я]*\\s+назначения|мест[а-я]*\\s+дислокации|Адрес\\s*:",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CUSTOMER_MARKER = Pattern.compile(
            "ГКП\\s+на\\s+ПХВ|КГП\\s+на\\s+ПХВ|ГККП|Некоммерческое\\s+акционерное"
                    + "|Акционерное\\s+общество|Коммунальное\\s+государственное",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // фикстура,          фрагмент имени заказчика,        фрагмент его адреса
            "lot-4875083-t1,      кардиологии и внутренних,        Айтеке би",
            "lot-4872223-t1,      областной центр психического,    Габит Мусирепов",
            "lot-4875003-t1,      Жалагашская районная,            Ныгмет Мырзалиев",
            "lot-4854483-t1,      Павлодарская областная детская,  Каукена Кенжетаева",
            "lot-1040829-t1,      Национальный центр детской,      Туран",
            "lot-1040409-t1,      Панфиловский район,              Головацкий",
    })
    void skSpec_emailTextCarriesNoCustomerOrAddress(String fixture, String customerFragment,
                                                    String addressFragment) throws IOException {
        String email = LotDescriptiveText.requirementsForEmail(realSpec(fixture));

        assertThat(email).as("имя заказчика в письме: %s", fixture).doesNotContain(customerFragment);
        assertThat(email).as("адрес заказчика в письме: %s", fixture).doesNotContain(addressFragment);
        assertThat(DELIVERY_MARKER.matcher(email).find())
                .as("маркер места поставки в письме: %s", fixture).isFalse();
        assertThat(CUSTOMER_MARKER.matcher(email).find())
                .as("орг-форма заказчика в письме: %s", fixture).isFalse();
    }

    /**
     * Матчинг комплектности работает по ПОЛНОМУ тексту и срезом не задет — срез живёт только в
     * {@link LotDescriptiveText#requirementsForEmail}. Проверяем на самом длинном хвосте: там же
     * видно, что разница между слоями реальна, а не случайна.
     */
    @Test
    void commercialTail_cutForEmailOnly_matchingKeepsFullText() throws IOException {
        String spec = realSpec("lot-4854483-t1");
        assertThat(LotDescriptiveText.forMatching("МРТ", null, spec)).contains("Павлодарская областная детская");
        assertThat(LotDescriptiveText.requirementsForEmail(spec)).doesNotContain("Павлодарская областная детская");
    }

    /**
     * Ключевой случай: таблица требований КОРОТКАЯ, поэтому коммерческий блок попадает внутрь лимита
     * обрезки письма — раньше заказчика удерживал только запас длины, то есть свойство данных.
     * <p>Фикстура {@code lot-1040829-t1-short-table} — это РЕАЛЬНОЕ ТЗ {@code lot-1040829-t1}, у
     * которого вырезан кусок строк таблицы комплектации (с «Максимально допустимая масса пациента»
     * по «Расходные материалы…»); шапка, оставшиеся требования и весь коммерческий блок — verbatim.
     * Ни одно из 39 живых ТЗ такой формы не имеет (самый ранний якорь там 2408 при лимите 1200),
     * поэтому случай построен усечением настоящего ТЗ, а не выдуман.
     * <p>{@code forMatching} здесь играет роль «как было бы без среза»: это тот же путь сегментации
     * без него.
     */
    @Test
    void shortRequirementsTable_customerWouldLandInsideLimit_butIsCutOut() throws IOException {
        String spec = realSpec("lot-1040829-t1-short-table");

        String withoutCut = LotDescriptiveText.forMatching(null, null, spec);
        assertThat(withoutCut.indexOf("Некоммерческое акционерное"))
                .as("без среза заказчик обязан попадать в обрезку письма — иначе случай не тот")
                .isBetween(0, 1199);
        assertThat(withoutCut.indexOf("Туран")).isBetween(0, 1199);

        String email = LotDescriptiveText.requirementsForEmail(spec);
        assertThat(email).doesNotContain("Некоммерческое акционерное").doesNotContain("Туран");
        assertThat(DELIVERY_MARKER.matcher(email).find()).isFalse();
        // требования остались целиком — срез снял только коммерческую часть
        assertThat(email).contains("Система тренировки и оценки ходьбы")
                .contains("3495 × 2141 × 2458 мм")
                .contains("Не более 240 В");
    }

    /**
     * goszakup-путь срез не трогает — и не должен: там порядок обратный, характеристики идут ПОСЛЕ
     * условий поставки (живой лот 2865), и слепой срез по тем же якорям съел бы реальные требования.
     * Гейт — заголовок документа «Техническая спецификация», которого у goszakup-ТЗ нет.
     */
    @Test
    void goszakupSpec_commercialAnchorsDoNotTruncate() {
        String goszakupTz = String.join("\n",
                "Наименование лота: Устройство оцифровки рентген снимков",
                "Условия поставки (в соответствии с ИНКОТЕРМС 2010)*: DDP",
                "Место поставки*: Карагандинская область, г.Караганда",
                "Описание требуемых характеристик закупаемого товара",
                "Тип детектора: цифровой плоскопанельный, Вес: не более 4,0 кг");
        String email = LotDescriptiveText.requirementsForEmail(goszakupTz);
        assertThat(email).contains("Тип детектора").contains("не более 4,0 кг");
    }
}
