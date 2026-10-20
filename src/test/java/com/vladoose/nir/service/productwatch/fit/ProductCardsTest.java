package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Карточка нашего товара: факты с сайта, из карточки РУ и описания; стемы для признаков (спека §4.3). */
class ProductCardsTest {

    static SiteProduct product(String name, String description, String reg, SiteProductAttribute... attrs) {
        return SiteProduct.builder().id(7L).market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand("Аксион")
                .slug("omh").description(description).registrationCert(reg).attributes(new java.util.ArrayList<>(List.of(attrs)))
                .syncedAt(OffsetDateTime.now()).build();
    }

    /** ОМХ-5/80 «Аксион» — характеристики сайта как в админ-API (снимок 2026-10-20). */
    @Test
    void siteAttributes_numbersRangesAndCounts() {
        ProductCard c = ProductCards.of(product("Отсасыватель медицинский хирургический ОМХ-5/80–01 «Аксион»", null, "РК-МТ-5№022493",
                new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"),
                new SiteProductAttribute("Количество ламп", "6")), null);
        ProductFact flow = c.facts().stream().filter(f -> f.unit() == SpecUnit.L_MIN).findFirst().orElseThrow();
        assertThat(flow.cmp()).isEqualTo(SpecCmp.MIN);
        assertThat(flow.lo()).isEqualTo(15.0);
        assertThat(flow.source()).isEqualTo(ProductCards.SITE);
        assertThat(flow.text()).isEqualTo("Производительность по воздуху: не менее 15 л/мин");
        ProductFact vac = c.facts().stream().filter(f -> f.unit() == SpecUnit.KPA).findFirst().orElseThrow();
        assertThat(vac.lo()).isEqualTo(5.0);
        assertThat(vac.hi()).isEqualTo(80.0);
        ProductFact lamps = c.facts().stream().filter(f -> f.unit() == null).findFirst().orElseThrow();
        assertThat(lamps.lo()).isEqualTo(6.0);
        assertThat(c.regStatus()).isEqualTo(RegStatus.UNKNOWN);   // № РУ есть, проверки ещё не было
    }

    /** Review Focus #3: единица — в названии характеристики («Мощность, Вт» → «30»), «не более» в названии — условие. */
    @Test
    void unitFromAttributeName() {
        ProductCard c = ProductCards.of(product("Бактерицидный облучатель ОБПЕ-450", null, null,
                new SiteProductAttribute("Мощность, Вт", "30"),
                new SiteProductAttribute("Потребляемая мощность, не более, ВА", "300"),
                new SiteProductAttribute("Средний срок службы, лет", "5")), null);
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.W);
            assertThat(f.lo()).isEqualTo(30.0);
            assertThat(f.cmp()).isEqualTo(SpecCmp.APPROX);
        });
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.VA);
            assertThat(f.cmp()).isEqualTo(SpecCmp.MAX);
            assertThat(f.hi()).isEqualTo(300.0);
        });
        assertThat(c.facts()).anySatisfy(f -> assertThat(f.unit()).isEqualTo(SpecUnit.YEAR));
        assertThat(c.regStatus()).isEqualTo(RegStatus.NOT_SPECIFIED);
    }

    @Test
    void registrationTechCharsAreFacts_andStemsCoverDescriptionAndPurpose() {
        RegistrationInfo reg = new RegistrationInfo("РК-МТ-7№010893", RegStatus.ACTIVE, null, true,
                "резания, монополярной и биполярной коагуляции биологических тканей", "Общая хирургия",
                "Масса составных частей аппарата (не более): Блок 4,5 кг; Габаритные размеры блока (не более): 295/295/100 мм",
                List.of("Педаль двухклавишная"));
        ProductCard c = ProductCards.of(product("ВЧ электрохирургический блок ЭХВЧ-80-03", "Аппарат с режимом биполярной коагуляции.",
                "РК-МТ-7№010893"), reg);
        assertThat(c.regActive()).isTrue();
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.REG);
        });
        assertThat(FitText.overlap(FitText.stems("биполярная коагуляция"), c.stems())).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("педаль"), c.stems())).isEqualTo(1.0);
    }

    @Test
    void synonymsAndPrefixes() {
        assertThat(FitText.overlap(FitText.stems("уровень вакуума"), FitText.stems("устанавливаемое разрежение"))).isEqualTo(0.5);
        assertThat(FitText.overlap(FitText.stems("количество ламп"), FitText.stems("лампы, количество"))).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("тип крепления"), FitText.stems("способ размещения"))).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("габариты"), FitText.stems("размещение"))).isZero();   // «размер» ≠ «размещ»
        assertThat(FitText.stems("наличие таймера")).containsExactly("таймер");                    // «наличие» — служебное
    }

    @Test
    void expiredRegistration_notActive() {
        RegistrationInfo reg = new RegistrationInfo("РК-МТ-5№022493", RegStatus.EXPIRED, LocalDate.of(2026, 6, 28), false,
                null, null, null, List.of());
        ProductCard c = ProductCards.of(product("ОМХ-5/80", null, "РК-МТ-5№022493"), reg);
        assertThat(c.regActive()).isFalse();
        assertThat(c.regExpires()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(RegStatus.EXPIRED.noRuText()).isEqualTo("РУ истекло");
    }
}
