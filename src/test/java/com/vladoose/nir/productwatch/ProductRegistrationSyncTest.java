package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.ProductRegistrationSync;
import com.vladoose.nir.service.productwatch.ProductRegistrationWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверка РУ товаров сайта (спека §4.2): действует / истекло / не найдено / сбой — прежние данные остаются. */
@SpringBootTest
@Transactional
class ProductRegistrationSyncTest {

    @Autowired SiteProductRepository products;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired ProductRegistrationWriter writer;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    final Map<String, NddaRegistration> found = new HashMap<>();
    final Set<String> failing = new HashSet<>();
    final List<Long> sleeps = new ArrayList<>();

    final NddaClient ndda = new NddaClient() {
        @Override public Long resolveId(String regNumber) { throw new UnsupportedOperationException(); }
        @Override public NddaRegistration findRegistration(String regNumber) {
            if (failing.contains(regNumber)) throw new UpstreamException("НЦЭЛС недоступен: timeout", null);
            return found.get(regNumber);
        }
        @Override public NddaDetailDto fetchDetail(long id) {
            NddaDetailDto d = new NddaDetailDto();
            d.setPurpose("для резания и коагуляции");
            d.setShortTechnicalCharacteristicsRu("Масса блока 4,5 кг");
            return d;
        }
        @Override public List<NddaComplectItemDto> fetchComplectList(long nddaId) {
            NddaComplectItemDto c = new NddaComplectItemDto();
            c.setComponent("Педаль двухклавишная");
            return List.of(c);
        }
    };

    ProductRegistrationSync sync() {
        return new ProductRegistrationSync(products, ndda, writer, tm, () -> {}, ms -> sleeps.add(ms));
    }

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        registrations.deleteAllInBatch();
        products.deleteAllInBatch();   // каталог nirdb (с № РУ после загрузки админ-API) — не наш набор; откатится с тестом
    }

    @AfterEach void clear() { MarketContext.clear(); }

    void product(String reg) {
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("Товар " + reg).registrationCert(reg)
                .syncedAt(OffsetDateTime.now()).build());
    }

    @Test
    void activeExpiredNotFound_numbersNormalizedAndDistinct() {
        product("РК-МТ-7№010893");
        product(" РК-МТ-7№010893 ");                 // тот же № с пробелами — один запрос
        product("РК-МТ-5№022493");
        product("РК-МТ-5№000000");
        product(null);                               // без № — не запрашивается
        found.put("РК-МТ-7№010893", new NddaRegistration(1, "РК-МТ-7№010893", "ЭХВЧ-80", "ФОТЕК", "РОССИЯ", LocalDate.of(2019, 11, 25), null, true, true));
        found.put("РК-МТ-5№022493", new NddaRegistration(2, "РК-МТ-5№022493", "ОМХ-5/80", "Аксион", "РОССИЯ", LocalDate.of(2021, 6, 28),
                LocalDate.of(2026, 6, 28), false, false));
        ProductRegistrationSync.Result r = sync().sync();
        assertThat(r.numbers()).isEqualTo(3);
        assertThat(r.active()).isEqualTo(1);
        assertThat(r.expired()).isEqualTo(1);
        assertThat(r.notFound()).isEqualTo(1);
        assertThat(sleeps).containsOnly(ProductRegistrationSync.PAUSE_MS).hasSize(3);
        ProductRegistration a = registrations.findById("РК-МТ-7№010893").orElseThrow();
        assertThat(a.getStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(a.getTechChars()).isEqualTo("Масса блока 4,5 кг");
        assertThat(a.getComplect()).extracting(RegistrationComponent::component).containsExactly("Педаль двухклавишная");
        assertThat(registrations.findById("РК-МТ-5№022493").orElseThrow().getExpirationDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(registrations.findById("РК-МТ-5№000000").orElseThrow().getStatus()).isEqualTo(RegStatus.NOT_FOUND);
    }

    /** Сбой номера — прежний статус и данные остаются, в итоге «ошибок 1»; номера без прежних данных — UNKNOWN. */
    @Test
    void failureKeepsPrevious() {
        product("РК-МТ-7№010893");
        product("РК-МТ-5№111111");
        found.put("РК-МТ-7№010893", new NddaRegistration(1, "РК-МТ-7№010893", "ЭХВЧ-80", "ФОТЕК", "РОССИЯ", null, null, true, true));
        sync().sync();
        product("РК-МТ-5№222222");                   // новый номер — первая же проверка со сбоем
        failing.add("РК-МТ-7№010893");
        failing.add("РК-МТ-5№111111");
        failing.add("РК-МТ-5№222222");
        ProductRegistrationSync.Result r = sync().sync();
        assertThat(r.errors()).isEqualTo(3);
        ProductRegistration kept = registrations.findById("РК-МТ-7№010893").orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(kept.getError()).contains("НЦЭЛС недоступен");
        assertThat(registrations.findById("РК-МТ-5№111111").orElseThrow().getStatus())
                .isEqualTo(RegStatus.NOT_FOUND);       // первый проход: не найдено; сбой его не стёр
        assertThat(registrations.findById("РК-МТ-5№222222").orElseThrow().getStatus())
                .isEqualTo(RegStatus.UNKNOWN);         // прежних данных нет — «не проверено»
        assertThat(r.text()).contains("ошибок 3");
    }
}
