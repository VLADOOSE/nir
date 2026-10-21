package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.CatalogSnapshot;
import com.vladoose.nir.service.productwatch.LotInput;
import com.vladoose.nir.service.productwatch.LotMatch;
import com.vladoose.nir.service.productwatch.ProductMatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Шум «🏷 наш вид» (пачки бота, выгрузка прода 20.10: 46 ложных отметок из 93). Слово группы — в названии лота; описание
 * считается, только когда название родовое (ЕНС ТРУ «Изделия различные прочие…», «Аппарат»); работы и услуги — не товар.
 * Тексты — настоящие лоты прода. Группы — сид V36–V38: 4 ИВЛ, 2 ЭКГ, 3 дефибрилляторы, 5 облучатели, 9 электрохирургия,
 * 10 отсасыватели, 15 стойки, 18 индикаторы стерилизации.
 */
class ProductMatcherNoiseTest {

    static CatalogSnapshot snap;

    /** Второй лот: объявление много-лотовое — название объявления в правила не идёт. */
    static final LotInput OTHER = new LotInput(99, "Бинт", "марлевый", null);

    @BeforeAll
    static void load() {
        snap = CatalogSnapshot.build(ProductFixtures.seedGroups(), ProductFixtures.catalogFull());
    }

    private static List<LotMatch> match(String announcement, LotInput... lots) {
        return ProductMatcher.match(snap, announcement, List.of(lots)).stream().filter(m -> m.lotId() != 99).toList();
    }

    @Test
    void wordOnlyInDescription_nameIsAnotherItem_notOurKind() {
        // 523984-1: «стойкий к жидкостям» — не стойка
        assertThat(match("Закуп", new LotInput(1, "Халат медицинский одноразовый, размер 50-52 (L-XL)",
                "Одноразовый стойкий к жидкостям, длина до середины голени, закрытый спереди, длинные завязки на шее и на поясе. "
                        + "Изготовлен из нетканого материала.", null), OTHER)).isEmpty();
        // 523984-1: «воротником-стойкой» в составе комплекта
        assertThat(match("Закуп", new LotInput(2, "Комплект одежды защитной врача-инфекциониста размер 46-48 (M)",
                "Очки защитные 1шт; Нарукавники медицинские на резинке 1шт; Халат хирургический с запахом и стойкой "
                        + "(противоэпидемический) 1шт; Горловина обработана воротником-стойкой с завязками. Изготовлена из нетканого "
                        + "материала.", null), OTHER)).isEmpty();
        // 525004-1: части набора — «Чехол для аспирации», «Очиститель наконечника коагулятора», «Карандаш электрохирургический»
        assertThat(match("Закуп", new LotInput(3, "Комплект для общей операции на бедре, стерильный, одноразового применения",
                "1. Простыня для стола (усиленная) 150 см х 250 см - 2 шт. 6. Чехол для аспирации и диатермии 35 см х 43 см - 1 шт. "
                        + "9. Очиститель наконечника коагулятора - 1 шт. 10. Карандаш электрохирургический - 1 шт.", null), OTHER)).isEmpty();
        // 17828334-1: баллон «для аппарата искусственной вентиляции легких» — не ИВЛ
        assertThat(match("Приобретение товаров на октябрь месяц",
                new LotInput(4, "Баллон кислородный", "для аппарата искусственной вентиляции легких", null), OTHER)).isEmpty();
        // 17827765-1: ЭКГ-электроды «для электрокардиографа» — не электрокардиограф
        assertThat(match("Приобретение изделий медицинских назначений (ИМН)",
                new LotInput(5, "Электрод специализированный", "для электрокардиографа", null), OTHER)).isEmpty();
    }

    @Test
    void genericLotName_wordInDescriptionCounts() {
        // 17826219-1: ЕНС ТРУ «Изделия различные прочие…» — предмет назван только в описании
        assertThat(match("Приобретение ИМН", new LotInput(1, "Изделия различные прочие, не включенные в другие группировки",
                "БИОЛОГИЧЕСКИЙ автономный индикатор одноразовый, предназначенный для контроля эффективности процесса ПАРОВОЙ "
                        + "СТЕРИЛИЗАЦИИ.", null), OTHER))
                .anyMatch(m -> m.groupId() == 18 && m.level() == ProductMatchLevel.TYPE);
        assertThat(match("Закуп медицинского оборудования",
                new LotInput(2, "Аппарат", "искусственной вентиляции легких для взрослых и детей", null), OTHER))
                .anyMatch(m -> m.groupId() == 4 && m.level() == ProductMatchLevel.TYPE);
    }

    @Test
    void wordInLotName_orSingleLotAnnouncement_stillCounts() {
        assertThat(match("Закуп", new LotInput(1, "Стойка", "инфузионная медицинская передвижная", null), OTHER))
                .anyMatch(m -> m.groupId() == 15);
        assertThat(match("Закуп", new LotInput(2, "Облучатель", "бактерицидный настенный", null), OTHER))
                .anyMatch(m -> m.groupId() == 5);
        // 17798368-1: объявление из одного лота — «Облучатель бактерицидный настенный», а код ЕНС ТРУ — «Лампа бактерицидная»
        assertThat(match("Облучатель бактерицидный настенный. Наличие сертификата обязателен.",
                new LotInput(3, "Лампа бактерицидная", "для обеззараживания воздуха и поверхностей в помещении", null)))
                .anyMatch(m -> m.groupId() == 5);
    }

    @Test
    void worksAndServices_notOurProduct() {
        // слово группы — в названии лота, но лот — работа или услуга (сильный маркер импорта, по названию)
        assertThat(match("Закуп", new LotInput(1, "Ремонт облучателей бактерицидных", "", null), OTHER)).isEmpty();
        assertThat(match("Закуп", new LotInput(2, "Услуги по поверке дефибрилляторов", "", null), OTHER)).isEmpty();
        assertThat(match("Закуп", new LotInput(3, "Услуги по техническому обслуживанию аппаратов ФОТЕК", "", null), OTHER))
                .as("и бренд: обслуживание — не продажа").isEmpty();
    }
}
