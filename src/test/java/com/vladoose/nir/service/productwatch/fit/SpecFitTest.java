package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Сверка «требования ТЗ ↔ наш товар» и вердикт (спека §6). Ожидания — руками по тексту ТЗ и характеристикам. */
class SpecFitTest {

    static ProductCard card(long id, String name, RegStatus reg, SiteProductAttribute... attrs) {
        SiteProduct p = SiteProduct.builder().id(id).market(Market.KZ).siteId(UUID.randomUUID()).name(name).slug("p" + id)
                .registrationCert("РК-1").attributes(new ArrayList<>(List.of(attrs))).syncedAt(OffsetDateTime.now()).build();
        return ProductCards.of(p, new RegistrationInfo("РК-1", reg, null, true, null, null, null, List.of()));
    }

    static List<SpecRequirement> reqs(String body) { return SpecRequirements.extract(body); }

    static final String ASPIRATOR = "работа от электрической сети; вакуумная система отсасывания; "
            + "производительность отсасывания: примерно 15–30 л/мин; уровень вакуума: до 0–75 кПа; "
            + "наличие ёмкости для сбора отсасываемой жидкости; система защиты от переполнения";

    static ProductCard omh(RegStatus reg) {
        return card(1, "Отсасыватель медицинский хирургический ОМХ-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости",
                reg,
                new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"));
    }

    @Test
    void aspirator_fits_withActiveRu() {
        FitCandidate c = SpecFit.compare(reqs(ASPIRATOR), omh(RegStatus.ACTIVE));
        assertThat(c.failed()).isZero();
        assertThat(c.met()).isGreaterThanOrEqualTo(3);
        assertThat(c.verdict()).isEqualTo(FitVerdict.FITS);
        assertThat(c.rows()).anySatisfy(r -> {
            assertThat(r.mark()).isEqualTo(FitMark.OK);
            assertThat(r.text()).contains("15–30");
            assertThat(r.ours()).isEqualTo("Производительность по воздуху: не менее 15 л/мин");
            assertThat(r.source()).isEqualTo(ProductCards.SITE);
        });
        assertThat(c.rows()).anySatisfy(r -> {                  // «защита от переполнения» — о ней нигде не сказано
            assertThat(r.text()).contains("переполнения");
            assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN);
        });
    }

    /** РУ истекло — «подходит по ТЗ, но РУ истекло» (ОМХ-5/80: РК-МТ-5№022493 истекло 28.06.2026). */
    @Test
    void expiredRu_fitsNoRu() {
        assertThat(SpecFit.compare(reqs(ASPIRATOR), omh(RegStatus.EXPIRED)).verdict()).isEqualTo(FitVerdict.FITS_NO_RU);
    }

    /** Облучатель Абай: «количество ламп — 1» против шестилампового — провал по счётному; ✗ сверху строк. */
    @Test
    void irradiator_lampCountFails_failRowFirst() {
        ProductCard obpe = card(2, "Бактерицидный облучатель ОБПЕ-450 шестиламповый передвижной", RegStatus.ACTIVE,
                new SiteProductAttribute("Количество ламп", "6"), new SiteProductAttribute("Мощность, Вт", "30"),
                new SiteProductAttribute("Сеть питания", "220 В 50 Гц"));
        FitCandidate c = SpecFit.compare(reqs("Количество ламп - 1\nМощность лампы - 20 Вт\nНапряжение - 220 В\nЧастота - 50 Гц"), obpe);
        assertThat(c.verdict()).isEqualTo(FitVerdict.NOT_FIT);
        assertThat(c.rows().get(0).mark()).isEqualTo(FitMark.FAIL);
        assertThat(c.reasonShort()).isEqualTo("количество ламп");
        assertThat(c.reason()).contains("у нас").contains("6");
        assertThat(c.rows()).anySatisfy(r -> {                  // 20 Вт против 30 Вт без условия — «отличается», не провал
            assertThat(r.text()).contains("20");
            assertThat(r.mark()).isEqualTo(FitMark.DIFF);
        });
    }

    /** «до X»: масса — «не более», вакуум — «дотянуться до X». */
    @Test
    void uptoBySense() {
        ProductCard heavy = card(3, "Аппарат", RegStatus.ACTIVE, new SiteProductAttribute("Масса", "12 кг"),
                new SiteProductAttribute("Разрежение", "до -60 кПа"));
        FitCandidate c = SpecFit.compare(reqs("масса до 10 кг; разрежение до 75 кПа; работа от сети"), heavy);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("масса")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.FAIL));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("разрежение")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.FAIL));
    }

    /**
     * Диапазон ТЗ против нашего диапазона — диапазон регулировки: ✓, только если наш покрывает его целиком; частично — ≈
     * (решает человек), не пересекаются — ✗ (проверка на проде 2026-10-20: ИВЛ под HAMILTON — «концентрация кислорода
     * 21–100 %» против нашего «60–100 %» стояла ✓). Одно наше значение внутри — ✓; сеть питания — по пересечению;
     * «0–X» — нижняя граница не требование («вакуум 0–75 кПа» — «до 75»).
     */
    @Test
    void rangeRequirement_ourRangeMustCoverIt() {
        ProductCard ivl = card(20, "Аппарат ИВЛ", RegStatus.ACTIVE,
                new SiteProductAttribute("Концентрация кислорода", "60–100 %"),
                new SiteProductAttribute("Дыхательный объем", "от 50 до 3000 мл"),
                new SiteProductAttribute("Разрежение", "от -5 до -80 кПа"),
                new SiteProductAttribute("Частота сети", "50 ± 2,5 Гц"),
                new SiteProductAttribute("Температура хранения", "25 °C"));
        assertThat(mark("концентрация кислорода 21 – 100%; масса не более 5 кг", ivl, "концентрация")).isEqualTo(FitMark.DIFF);
        assertThat(mark("дыхательный объем 20 – 2000 мл; масса не более 5 кг", ivl, "дыхательный")).isEqualTo(FitMark.DIFF);
        assertThat(mark("дыхательный объем 50 – 2000 мл; масса не более 5 кг", ivl, "дыхательный")).isEqualTo(FitMark.OK);
        assertThat(mark("концентрация кислорода 21 – 50%; масса не более 5 кг", ivl, "концентрация")).isEqualTo(FitMark.FAIL);
        assertThat(mark("уровень вакуума 0 – 75 кПа; масса не более 5 кг", ivl, "уровень")).isEqualTo(FitMark.OK);
        assertThat(mark("частота 50 – 60 Гц; масса не более 5 кг", ivl, "частота")).isEqualTo(FitMark.OK);
        assertThat(mark("температура хранения 18 – 30 °C; масса не более 5 кг", ivl, "температура")).isEqualTo(FitMark.OK);
    }

    static FitMark mark(String spec, ProductCard card, String rowStart) {
        return SpecFit.compare(reqs(spec), card).rows().stream().filter(r -> r.text().startsWith(rowStart))
                .findFirst().orElseThrow(() -> new AssertionError("нет строки «" + rowStart + "» в " + spec)).mark();
    }

    /** «не менее 20» при нашем «не менее 15» — не доказано (нет данных), а не провал. */
    @Test
    void minAgainstOurMin_unknown() {
        FitCandidate c = SpecFit.compare(reqs("производительность не менее 20 л/мин; работа от сети"), omh(RegStatus.ACTIVE));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("20")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.failed()).isZero();
    }

    @Test
    void fewRequirements_noRequirements_andPartial() {
        assertThat(SpecFit.evaluate(reqs("медицинский"), List.of(omh(RegStatus.ACTIVE)), null).verdict())
                .isEqualTo(FitVerdict.NO_REQUIREMENTS);
        // 3 требования, ✓ 2 — меньше трёх подтверждённых: «похоже» (FITS_MIN_MET)
        FitCandidate two = SpecFit.compare(reqs("работа от электрической сети; вакуумная система отсасывания; цветной дисплей"),
                omh(RegStatus.ACTIVE));
        assertThat(two.met()).isEqualTo(2);
        assertThat(two.verdict()).isEqualTo(FitVerdict.PARTIAL);
        // 7 требований, ✓ 3 — меньше половины: «похоже» (FITS_SHARE)
        FitCandidate share = SpecFit.compare(reqs("работа от электрической сети; вакуумная система отсасывания; "
                + "производительность отсасывания: примерно 15–30 л/мин; цветной дисплей; режим мониторирования; "
                + "индикация разряда аккумулятора; наличие встроенного термопринтера"), omh(RegStatus.ACTIVE));
        assertThat(share.total()).isEqualTo(7);
        assertThat(share.met()).isEqualTo(3);
        assertThat(share.verdict()).isEqualTo(FitVerdict.PARTIAL);
    }

    /** Лучший — по вердикту; варианты — до 2 со ✓ > 0; «ТЗ под X» — в итоге лота. */
    @Test
    void evaluate_bestFirstAndAlternatives() {
        ProductCard empty = card(9, "Кабель", RegStatus.ACTIVE);
        ProductCard expired = omh(RegStatus.EXPIRED);
        ProductCard active = card(5, "Отсасыватель ОМП-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости",
                RegStatus.ACTIVE, new SiteProductAttribute("Производительность", "не менее 20 л/мин"),
                new SiteProductAttribute("Разрежение", "от 5 до 80 кПа"));
        LotFit fit = SpecFit.evaluate(reqs(ASPIRATOR), List.of(empty, expired, active), "BOWA");
        assertThat(fit.verdict()).isEqualTo(FitVerdict.FITS);
        assertThat(fit.best().productId()).isEqualTo(5);
        assertThat(fit.candidates()).extracting(FitCandidate::productId).containsExactly(5L, 1L);   // «Кабель» без ✓ — не вариант
        assertThat(fit.foreignBrand()).isEqualTo("BOWA");
    }

    /**
     * Калибровка (задача 14): единственный факт семейства без совпадения имени — только для питания. «Диаметр электрода
     * 2,4 мм» против «длина кабеля 3 м» — не сверка (17383745-1 давал ложное «подходит»); «напряжение» против «сеть питания» — да.
     */
    @Test
    void singleFactWithoutName_onlyForSupply() {
        ProductCard cable = card(7, "Кабель", RegStatus.ACTIVE, new SiteProductAttribute("Длина кабеля", "3 м"),
                new SiteProductAttribute("Сеть питания", "220 В"));
        FitCandidate c = SpecFit.compare(reqs("диаметр электрода 2,4 мм; напряжение - 220 В"), cable);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("диаметр")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("напряжение")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }
    /**
     * Калибровка (задача 14): факт — того же параметра, а не по одному общему слову. ДКИ-Н-11 против ТЗ ДКИ-Н-12
     * (17733214-1): «время установления рабочего режима не более 3 с» цеплялось к «время непрерывной работы от сети не
     * менее 168 ч» и давало ложное ✗; совпало одно слово из четырёх — пары нет, «нет данных».
     */
    @Test
    void weakNameMatch_isNotAPair() {
        ProductCard dki = card(8, "Дефибриллятор-монитор", RegStatus.ACTIVE,
                new SiteProductAttribute("Время непрерывной работы от сети", "не менее 168 ч"),
                new SiteProductAttribute("Время установления рабочего режима", "не более 3 с"));
        FitCandidate same = SpecFit.compare(reqs("время установления рабочего режима не более 3 с; масса не более 4 кг"), dki);
        assertThat(same.rows()).filteredOn(r -> r.text().contains("установления")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
        ProductCard other = card(9, "Дефибриллятор-монитор", RegStatus.ACTIVE,
                new SiteProductAttribute("Время непрерывной работы от сети", "не менее 168 ч"));
        FitCandidate c = SpecFit.compare(reqs("время установления рабочего режима не более 3 с; масса не более 4 кг"), other);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("установления")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.failed()).isZero();
    }

    /**
     * Калибровка (задача 14): без совпадения имени — только сеть питания (100–400 В, 45–65 Гц) и у требования, и у факта.
     * ЭК3Т-01-«Р-Д» против ТЗ 17719756-1: «частота дискретизации не менее 32 000 Гц» сверялась с «питание от сети 60 Гц» —
     * ложное ✗; «Li-ion не менее 14,8 В» с сетевыми 220 В — ложное ✓. Сетевой факт среди нескольких напряжений (220 В и
     * аккумулятор 12 В) — берётся.
     */
    @Test
    void factWithoutName_onlyMains() {
        ProductCard ecg = card(10, "Электрокардиограф", RegStatus.ACTIVE, new SiteProductAttribute("Сеть питания", "220 В 50 Гц"),
                new SiteProductAttribute("Аккумулятор", "12 В"));
        FitCandidate c = SpecFit.compare(reqs("частота дискретизации не менее 32 000 Гц; Li-ion не менее 14,8 В; "
                + "Напряжение - 220 В; Частота - 50 Гц"), ecg);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("дискретизации")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("14,8")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().startsWith("напряжение -")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
        assertThat(c.rows()).filteredOn(r -> r.text().startsWith("частота -")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }
    /** Калибровка (задача 14): «количество» — не имя параметра: «количество жил» провода и «количество ламп» — разные (17825385-1). */
    @Test
    void countWordIsNotTheParameter() {
        ProductCard obp = card(11, "Бактерицидный облучатель", RegStatus.ACTIVE, new SiteProductAttribute("Количество ламп", "2"));
        FitCandidate c = SpecFit.compare(reqs("провод ПВС: количество жил - 3; количество ламп - 2"), obp);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("жил")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("ламп")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }
    /**
     * Проверка на проде (2026-10-20), три ложных ✗: «частота 50 – 60 Гц» (CUSA, 526624-1) сравнивалась с «частота
     * модуляции … 75 Гц» — название требования из одного слова параметра по названию не сравнивается, сеть — прежним путём;
     * «потребляемая мощность не менее 90 Вт» (облучатель, 17822670-1) — с «Мощность 1 лампы, Вт: 15»: потребляемая — только с
     * потребляемой; «до высоты 4000 м» (ИВЛ, 526444-1) — с «−1 ±0,5 см H₂O»: это давление, не длина.
     */
    @Test
    void liveFalseFails_areNotPairs() {
        ProductCard actiton = card(12, "Блок управления ACTITON", RegStatus.ACTIVE,
                new SiteProductAttribute("Частота модуляции высокочастотного напряжения", "75 Гц"),
                new SiteProductAttribute("Сеть питания", "220 В 50 Гц"));
        FitCandidate c1 = SpecFit.compare(reqs("частота 50 – 60 Гц; масса не более 25 кг"), actiton);
        assertThat(c1.rows()).filteredOn(r -> r.text().startsWith("частота")).singleElement()
                .satisfies(r -> {
                    assertThat(r.mark()).isEqualTo(FitMark.OK);
                    assertThat(r.ours()).contains("Сеть питания");
                });
        ProductCard rb = card(13, "Рециркулятор бактерицидный", RegStatus.ACTIVE, new SiteProductAttribute("Мощность 1 лампы, Вт", "15"));
        FitCandidate c2 = SpecFit.compare(reqs("потребляемая мощность не менее 90 Вт; мощность лампы не более 30 Вт"), rb);
        assertThat(c2.rows()).filteredOn(r -> r.text().startsWith("потребляемая")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c2.rows()).filteredOn(r -> r.text().startsWith("мощность лампы")).singleElement()
                .satisfies(r -> {
                    assertThat(r.mark()).isEqualTo(FitMark.OK);
                    assertThat(r.ours()).startsWith("Мощность 1 лампы");
                });
        ProductCard rbFull = card(15, "Рециркулятор бактерицидный", RegStatus.ACTIVE, new SiteProductAttribute("Потребляемая мощность, Вт", "60"));
        assertThat(SpecFit.compare(reqs("потребляемая мощность не менее 90 Вт; масса не более 5 кг"), rbFull).rows())
                .filteredOn(r -> r.text().startsWith("потребляемая")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.FAIL));   // потребляемая с потребляемой — сверяется
        ProductCard ivl = card(14, "Аппарат ИВЛ", RegStatus.ACTIVE, new SiteProductAttribute("Триггер по давлению", "−1 ±0,5 см H₂O"));
        FitCandidate c3 = SpecFit.compare(reqs("автоматическая компенсация парциального давления до высоты 4000 м; масса не более 5 кг"), ivl);
        assertThat(c3.rows()).filteredOn(r -> r.text().contains("4000")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c3.failed()).isZero();
        // после правки единиц (тот же ТЗ HAMILTON-C6 на проде): триггер — только с триггером, не с управляемым давлением
        FitCandidate c4 = SpecFit.compare(reqs("управляемое давление (Pinsp) 3 - 100 cmH2O; триггер по давлению от 0,5 до 20 см вод. ст."), ivl);
        assertThat(c4.rows()).filteredOn(r -> r.text().startsWith("управляемое")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c4.rows()).filteredOn(r -> r.text().startsWith("триггер")).singleElement()
                .satisfies(r -> assertThat(r.ours()).startsWith("Триггер по давлению"));
        // ТЗ под BOWA ARC 303 (тот же прогон): «240 В·А» у нашего блока — вольт-амперы, не вольты; «при 100–120 В» не с чем сверять
        ProductCard esu = card(16, "ВЧ электрохирургический блок", RegStatus.ACTIVE,
                new SiteProductAttribute("Потребляемая мощность при номинальной выходной мощности", "240 В·А"));
        FitCandidate c5 = SpecFit.compare(reqs("потребляемый ток (максимальный): 6,4 а (при 100–120 В); масса не более 25 кг"), esu);
        assertThat(c5.rows()).filteredOn(r -> r.text().startsWith("потребляемый ток")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c5.failed()).isZero();
    }
    /**
     * Проверка на проде (2026-10-20, третий заход): общее у требования и факта — только слово самого параметра, а у
     * обоих свои уточнения — разные параметры: «давление PEEP/CPAP 0 – 50 cmH2O» (ИВЛ HAMILTON-C6) против
     * «эксплуатационное давление 270 – 600 кПа», «масса пустого баллона» против «масса комплекта». Факт без уточнения
     * («Мощность, Вт») и общее уточнение («время зарядки … батареи») — по-прежнему пара.
     */
    @Test
    void onlyParameterWordShared_isNotAPair() {
        ProductCard ivl = card(16, "Аппарат ИВЛ", RegStatus.ACTIVE,
                new SiteProductAttribute("Эксплуатационное давление", "от 270 до 600 кПа"),
                new SiteProductAttribute("Масса комплекта", "не более 12 кг"),
                new SiteProductAttribute("Время зарядки батареи", "не более 6 ч"));
        FitCandidate c = SpecFit.compare(reqs("давление PEEP/CPAP 0 – 50 cmH2O; масса пустого баллона не менее 58,4 кг; "
                + "время зарядки полностью разряженной батареи не более 8 часов"), ivl);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("peep")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("баллона")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("зарядки")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }
}
