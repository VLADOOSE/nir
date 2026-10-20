package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.TechSpecExtractor;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Снимок каталога сайта и ТЗ из src/test/resources/product-watch — для правил без базы. */
final class ProductFixtures {

    private ProductFixtures() {}

    record Item(String id, String name, String slug, String brand, String category, String shortDescription) {}

    static List<SiteProduct> catalog() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/site-catalog.json")) {
            List<Item> items = new ObjectMapper().readValue(in, new TypeReference<>() {});
            AtomicLong id = new AtomicLong(1000);
            List<SiteProduct> out = new ArrayList<>();
            for (Item i : items) {
                out.add(SiteProduct.builder().id(id.incrementAndGet()).market(Market.KZ).siteId(UUID.fromString(i.id()))
                        .name(i.name()).slug(i.slug()).brand(i.brand()).category(i.category())
                        .shortDescription(i.shortDescription()).syncedAt(OffsetDateTime.now()).build());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Русская часть ТЗ — тем же кодом, что разбор «ТЗ» в работе. */
    static String spec(String file) {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/specs/" + file)) {
            return TechSpecExtractor.russianSection(PdfTextExtractor.extract(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ProductGroup group(long id, String name, List<String> words, List<String> require, List<String> exclude, List<String> brands) {
        return ProductGroup.builder().id(id).market(Market.KZ).name(name).searchWords(words).requireWords(require)
                .excludeWords(exclude).brandWords(brands).active(true).position((int) id).build();
    }

    /** Группы как в сиде V36 (часть): бренды, ЭКГ, облучатели, светильники, отсасыватели, электроды, дозаторы, мониторы. */
    static List<ProductGroup> groups() {
        ProductGroup brands = group(1, "Наши бренды и модели", List.of("фотек", "fotek", "аксион", "эмалед", "эхвч", "эхвча", "митар", "актитон", "actiton"),
                List.of(), List.of(), List.of());
        brands.setBrandGroup(true);
        return List.of(brands,
                group(2, "Электрокардиографы", List.of("электрокардиограф"), List.of(), List.of(), List.of("аксион", "эк1т", "эк3тц", "эк12т", "эк3т", "крп-01", "нпп монитор")),
                group(5, "Облучатели и рециркуляторы", List.of("облучатель", "рециркулятор"), List.of("бактерицид", "кварц", "ультрафиолет", "рециркул", "обеззараж"), List.of(),
                        List.of("рбм", "рбпе", "рбмт", "обн", "обп", "обпе", "оуфну", "эма")),
                group(8, "Светильники", List.of("светильник"), List.of("операцион", "хирург", "бестенев", "смотров", "медицин", "процедур"),
                        List.of("уличн", "прожектор", "офисн", "аварийн", "взрывозащ"), List.of("эмалед")),
                group(10, "Отсасыватели и аспирация дыма", List.of("отсасыватель", "аспиратор", "аспирации"), List.of(), List.of("катетер", "назальн"),
                        List.of("асд", "фотек", "омх", "омп", "ог-10", "аксион")),
                group(13, "Нейтральные электроды", List.of("электрод"), List.of("нейтральн", "возвратн", "пассивн", "электрохирург"),
                        List.of("сварочн", "экг", "кардиограф", "сравнения", "искров"), List.of("фотек", "valleylab")),
                group(23, "Мониторы пациента", List.of("монитор"), List.of("пациент", "прикроват", "реанимац", "витальн"),
                        List.of("компьютер", "жидкокристал", "диагональ", "офисн"), List.of("митар", "нпп монитор")),
                group(24, "Дозаторы шприцевые", List.of("дозатор"), List.of("шприц", "инфузион"),
                        List.of("лаборатор", "пипет", "мыл", "антисептик", "локтев", "бумаж"), List.of("д01", "аксион")));
    }
}
