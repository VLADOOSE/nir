package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import com.vladoose.nir.service.productwatch.fit.ForeignBrand;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.TechSpecExtractor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Снимок каталога сайта и ТЗ из src/test/resources/product-watch — для правил без базы. */
public final class ProductFixtures {

    private ProductFixtures() {}

    record Item(String id, String name, String slug, String brand, String category, String shortDescription) {}

    public static List<SiteProduct> catalog() {
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

    record FullItem(String id, String name, String slug, String brand, String category, String shortDescription,
                    String description, List<SiteProductAttribute> attributes, String registrationCert) {}

    /** Снимок каталога с описаниями, характеристиками (с 2026-10-20 — и Фотек, Орион, задача 15) и № РУ (админ-API). */
    public static List<SiteProduct> catalogFull() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/site-catalog-full.json")) {
            ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<FullItem> items = om.readValue(in, new TypeReference<>() {});
            AtomicLong id = new AtomicLong(1000);
            List<SiteProduct> out = new ArrayList<>();
            for (FullItem i : items) {
                List<SiteProductAttribute> attrs = new ArrayList<>(i.attributes() == null ? List.of() : i.attributes());
                out.add(SiteProduct.builder().id(id.incrementAndGet()).market(Market.KZ).siteId(UUID.fromString(i.id()))
                        .name(i.name()).slug(i.slug()).brand(i.brand()).category(i.category())
                        .shortDescription(i.shortDescription()).description(i.description()).attributes(attrs)
                        .registrationCert(i.registrationCert()).syncedAt(OffsetDateTime.now()).build());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record RegItem(String regNumber, String status, LocalDate expires, Boolean unlimited, String purpose, String useArea,
                   String techChars, List<String> components) {}

    /** РУ наших товаров (снимок ndda.kz, задача 14) — для гейта сверки. */
    public static Map<String, RegistrationInfo> registrations() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/registrations.json")) {
            List<RegItem> items = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(in, new TypeReference<>() {});
            Map<String, RegistrationInfo> out = new HashMap<>();
            for (RegItem r : items) {
                out.put(r.regNumber(), new RegistrationInfo(r.regNumber(), RegStatus.valueOf(r.status()), r.expires(),
                        Boolean.TRUE.equals(r.unlimited()), r.purpose(), r.useArea(), r.techChars(),
                        r.components() == null ? List.of() : r.components()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Бренды из производителей реестра (снимок, задача 14) — как `ProductCatalog.refresh` в работе. */
    public static Set<String> registryBrands() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/registry-producers.txt")) {
            return ForeignBrand.registryBrands(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Строка набора сверки (fit-golden.tsv): файл ТЗ, лот, объявление, описание, ожидаемый вердикт, товар, чужой бренд, заметка. */
    public record FitGoldenRow(String file, String lot, String announcement, String description, String expected, String product,
                               String foreign, String note) {}

    public static List<FitGoldenRow> fitGolden() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/fit-golden.tsv")) {
            List<FitGoldenRow> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                out.add(new FitGoldenRow(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c.length > 7 ? c[7] : ""));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Строка набора сверки ТЗ (golden.tsv, задача 6): файл, ожидание, начало названия товара, группа, лот, объявление. */
    public record GoldenRow(String file, String expected, String productPrefix, String group, String lot, String announcement,
                            String description) {}

    public static List<GoldenRow> golden() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/golden.tsv")) {
            List<GoldenRow> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                out.add(new GoldenRow(c[0], c[1], c[2], c[3], c[4], c[5], c.length > 6 ? c[6] : ""));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Байты PDF ТЗ из ресурсов (для фейка клиента goszakup). */
    static byte[] pdf(String file) {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/specs/" + file)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Русская часть ТЗ — тем же кодом, что разбор «ТЗ» в работе. */
    public static String spec(String file) {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/specs/" + file)) {
            return TechSpecExtractor.russianSection(PdfTextExtractor.extract(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ProductGroup group(long id, String name, List<String> words, List<String> require, List<String> exclude, List<String> brands) {
        return ProductGroup.builder().id(id).market(Market.KZ).name(name).searchWords(words).requireWords(require)
                .excludeWords(exclude).brandWords(brands).active(true).position((int) id).build();
    }

    /** Группы как в сиде V36 (часть): бренды, ЭКГ, облучатели, светильники, отсасыватели, электроды, дозаторы, мониторы. */
    public static List<ProductGroup> groups() {
        ProductGroup brands = group(1, "Наши бренды и модели", List.of("фотек", "fotek", "аксион", "эмалед", "митар", "актитон", "actiton"),
                List.of(), List.of(), List.of());
        brands.setBrandGroup(true);
        return List.of(brands,
                group(2, "Электрокардиографы", List.of("электрокардиограф"), List.of(), List.of(), List.of("аксион", "эк1т", "эк3тц", "эк12т", "эк3т", "крп-01", "нпп монитор")),
                group(5, "Облучатели и рециркуляторы", List.of("облучатель", "рециркулятор"), List.of("бактерицид", "кварц", "ультрафиолет", "рециркул", "обеззараж"), List.of(),
                        List.of("рбм", "рбпе", "рбмт", "оуфну", "эма")),
                group(8, "Светильники", List.of("светильник"), List.of("операцион", "хирург", "бестенев", "смотров", "медицин", "процедур"),
                        List.of("уличн", "прожектор", "офисн", "аварийн", "взрывозащ"), List.of("эмалед")),
                group(10, "Отсасыватели и аспирация дыма", List.of("отсасыватель", "аспиратор", "аспирации"), List.of(),
                        List.of("катетер", "назальн", "сильфон", "пробоотбор"), List.of("асд", "фотек", "омх", "омп", "ог-10", "аксион")),
                group(13, "Нейтральные электроды", List.of("электрод"), List.of("нейтральн", "возвратн", "пассивн", "электрохирург"),
                        List.of("сварочн", "экг", "кардиограф", "сравнения", "искров"), List.of("фотек")),
                group(23, "Мониторы пациента", List.of("монитор"), List.of("пациент", "прикроват", "реанимац", "витальн"),
                        List.of("компьютер", "жидкокристал", "диагональ", "офисн"), List.of("митар", "нпп монитор")),
                group(24, "Дозаторы шприцевые", List.of("дозатор"), List.of("шприц", "инфузион"),
                        List.of("лаборатор", "пипет", "мыл", "антисептик", "локтев", "бумаж"), List.of("д01", "аксион")));
    }

    /** Все 24 группы сида рынка KZ — V36 с правками калибровки V37 и V38 (сверка с базой — ProductWatchSchemaTest.seedFixtureMirrorsMigration). */
    public static List<ProductGroup> seedGroups() {
        return List.of(
                seed(1, "Наши бренды и модели (части и принадлежности)", List.of("фотек", "fotek", "аксион", "эмалед", "митар", "актитон", "actiton"), List.of(), List.of(), List.of(), true),
                seed(2, "Электрокардиографы (Аксион, НПП Монитор)", List.of("электрокардиограф"), List.of(), List.of(), List.of("аксион", "эк1т", "эк3тц", "эк12т", "эк3т", "крп-01", "нпп монитор"), false),
                seed(3, "Дефибрилляторы (Аксион)", List.of("дефибриллятор"), List.of(), List.of(), List.of("аксион", "дки-н", "да-н"), false),
                seed(4, "Аппараты ИВЛ (Аксион)", List.of("вентиляции"), List.of("легк"), List.of("приточн", "вытяжн"), List.of("а-ивл"), false),
                seed(5, "Облучатели и рециркуляторы бактерицидные, УФ (ЭМА)", List.of("облучатель", "рециркулятор"), List.of("бактерицид", "кварц", "ультрафиолет", "рециркул", "обеззараж"), List.of(), List.of("рбм", "рбпе", "рбмт", "оуфну", "эма"), false),
                seed(6, "Облучатели фототерапевтические (Аксион)", List.of("облучатель", "фототерапии"), List.of("фототерап", "новорожд", "неонат"), List.of(), List.of("офтн", "аксион"), false),
                seed(7, "Обогрев новорождённых (Аксион)", List.of("обогрева"), List.of("новорожд", "неонат"), List.of(), List.of("уон", "аксион"), false),
                seed(8, "Светильники операционные и смотровые (ЭМАЛЕД)", List.of("светильник"), List.of("операцион", "хирург", "бестенев", "смотров", "медицин", "процедур"), List.of("уличн", "прожектор", "офисн", "аварийн", "взрывозащ"), List.of("эмалед"), false),
                seed(9, "Электрохирургия (Фотек)", List.of("электрохирургический", "электрокоагулятор", "коагулятор", "эхвч"), List.of(), List.of(), List.of("фотек", "onyx", "термошов"), false),
                seed(10, "Отсасыватели и аспирация дыма (Аксион, Фотек)", List.of("отсасыватель", "аспиратор", "аспирации"), List.of(), List.of("катетер", "назальн", "сильфон", "пробоотбор"), List.of("асд", "фотек", "омх", "омп", "ог-10", "аксион"), false),
                seed(11, "Ультразвуковая хирургия (Фотек ACTITON)", List.of("ультразвуковой"), List.of("хирург", "костн", "остеотом", "скальпел"), List.of(), List.of("actiton", "актитон", "фотек"), false),
                seed(12, "Инсуффляторы (Фотек)", List.of("инсуффлятор"), List.of(), List.of(), List.of("фотек"), false),
                seed(13, "Нейтральные электроды и принадлежности ЭХВЧ (Фотек)", List.of("электрод"), List.of("нейтральн", "возвратн", "пассивн", "электрохирург"), List.of("сварочн", "экг", "кардиограф", "сравнения", "искров"), List.of("фотек"), false),
                seed(14, "Биполярные инструменты (Фотек)", List.of("пинцет", "ножницы"), List.of("биполяр"), List.of(), List.of("фотек", "cleantips"), false),
                seed(15, "Стойки аппаратные и инфузионные (Фотек)", List.of("стойка"), List.of("инфузион", "аппарат", "медицин", "эндоскоп"), List.of("ресепшн", "серверн", "телекоммуникац", "стеллаж"), List.of("сп1-01", "сп2-01", "фотек"), false),
                seed(16, "Стерилизаторы озоновые (Орион)", List.of("стерилизатор"), List.of("озон"), List.of(), List.of("орион", "ск-16", "ск-36", "ск-40", "ск-85", "ск-140", "ск-250"), false),
                seed(17, "Озонотерапия (Орион)", List.of("озонатор", "озонотерапии"), List.of(), List.of(), List.of("орион", "от-15"), false),
                seed(18, "Индикаторы стерилизации (Орион)", List.of("индикатор"), List.of("стерилиз", "стерильн"), List.of(), List.of("озовин", "дезтест"), false),
                seed(19, "Магнитотерапия (ЭМА «Полюс»)", List.of("магнитотерапии"), List.of(), List.of(), List.of("полюс"), false),
                seed(20, "Электрофорез (ЭМА «Поток»)", List.of("электрофореза"), List.of("аппарат", "физиотерап", "гальван"), List.of("гель", "капилляр", "реагент", "белк"), List.of("поток-1"), false),
                seed(21, "Спирографы (НПП Монитор)", List.of("спирограф", "спирометр"), List.of(), List.of(), List.of("смп-02", "нпп монитор"), false),
                seed(22, "Холтер и кардиорегистраторы (НПП Монитор)", List.of("холтер", "кардиорегистратор", "мониторирования"), List.of("экг", "суточн", "холтер", "кардиорегистр"), List.of(), List.of("крн-01", "крб-02", "нпп монитор"), false),
                seed(23, "Мониторы пациента (НПП Монитор)", List.of("монитор"), List.of("пациент", "прикроват", "реанимац", "витальн"), List.of("компьютер", "жидкокристал", "диагональ", "офисн"), List.of("митар", "нпп монитор"), false),
                seed(24, "Дозаторы шприцевые (Аксион)", List.of("дозатор"), List.of("шприц", "инфузион"), List.of("лаборатор", "пипет", "мыл", "антисептик", "локтев", "бумаж"), List.of("д01", "аксион"), false));
    }

    private static ProductGroup seed(long position, String name, List<String> words, List<String> require, List<String> exclude,
                                     List<String> brands, boolean brandGroup) {
        ProductGroup g = group(position, name, words, require, exclude, brands);
        g.setBrandGroup(brandGroup);
        return g;
    }
}
