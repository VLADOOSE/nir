package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Карточки «модели в работе» (спека 2026-10-20-lot-model-in-work §6): модель реестра — по карточке РУ, позиция каталога — по описанию. */
class ProductCardsModelTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    static MedRegistry reg(Boolean unlimited, LocalDate expires) {
        return MedRegistry.builder().regNumber("РК МИ (МТ)-0№099001").name("Дефибриллятор BeneHeart D3").producer("Mindray")
                .purpose("Дефибрилляция и кардиоверсия").techChars("Энергия разряда: не менее 360 Дж; масса не более 6 кг")
                .unlimited(unlimited).expirationDate(expires).build();
    }

    @Test
    void registryCard_factsFromRuCard_andStatusBySrok() {
        ProductCard c = ProductCards.ofRegistry(reg(true, null), TODAY);
        assertThat(c.name()).isEqualTo("Дефибриллятор BeneHeart D3");
        assertThat(c.brand()).isEqualTo("Mindray");
        assertThat(c.regNumber()).isEqualTo("РК МИ (МТ)-0№099001");
        assertThat(c.regStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.REG);
        });
        assertThat(c.stems()).anyMatch(s -> s.startsWith("дефибр"));
        assertThat(ProductCards.ofRegistry(reg(false, TODAY.minusDays(1)), TODAY).regStatus()).isEqualTo(RegStatus.EXPIRED);
        assertThat(ProductCards.ofRegistry(reg(false, TODAY), TODAY).regStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(ProductCards.ofRegistry(reg(false, null), TODAY).regStatus()).isEqualTo(RegStatus.UNKNOWN);
    }

    @Test
    void registryCard_comparesWithSpec() {
        FitCandidate c = SpecFit.compare(SpecRequirements.extract("масса не более 8 кг; энергия разряда не менее 200 Дж"),
                ProductCards.ofRegistry(reg(true, null), TODAY));
        assertThat(c.rows()).filteredOn(r -> r.text().startsWith("масса")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }

    @Test
    void equipmentCard_factsFromSpec() {
        ProductCard c = ProductCards.ofEquipment(MedEquipment.builder().name("Монитор пациента").manufact("Завод")
                .spec("Масса не более 4 кг").build());
        assertThat(c.regStatus()).isEqualTo(RegStatus.UNKNOWN);
        assertThat(c.brand()).isEqualTo("Завод");
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.DESCRIPTION);
        });
    }

    /** Признак у модели реестра нашёлся в карточке РУ, у позиции каталога — в её описании: не «на сайте» (живая проверка). */
    @Test
    void featureRow_sourceByCardKind() {
        MedRegistry r = reg(true, null);
        r.setPurpose("Режим кардиоверсии и дефибрилляции");
        FitCandidate reg = SpecFit.compare(SpecRequirements.extract("наличие режима кардиоверсии"), ProductCards.ofRegistry(r, TODAY));
        assertThat(reg.rows()).singleElement().satisfies(row -> {
            assertThat(row.mark()).isEqualTo(FitMark.OK);
            assertThat(row.ours()).isEqualTo("есть в карточке РУ");
            assertThat(row.source()).isEqualTo(ProductCards.REG);
        });
        FitCandidate eq = SpecFit.compare(SpecRequirements.extract("наличие режима кардиоверсии"), ProductCards.ofEquipment(
                MedEquipment.builder().name("Дефибриллятор").manufact("Завод").spec("Режим кардиоверсии").build()));
        assertThat(eq.rows()).singleElement().satisfies(row -> {
            assertThat(row.ours()).isEqualTo("есть в описании модели");
            assertThat(row.source()).isEqualTo(ProductCards.DESCRIPTION);
        });
    }
}
