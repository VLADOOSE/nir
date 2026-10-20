package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Страница «Наша продукция» (спека §12.1): весь список одним PUT, проверка до записи, «Проверить». */
@SpringBootTest
@Transactional
class ProductGroupServiceTest {

    @Autowired ProductGroupRepository groups;
    @Autowired SiteProductRepository products;
    @Autowired ProductCatalog catalog;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    FakeGoszakupSearchClient search;
    ProductGroupService service;
    final List<String> rematches = new ArrayList<>();

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groups.deleteAllInBatch();
        em.flush();
        search = new FakeGoszakupSearchClient();
        service = new ProductGroupService(groups, products, catalog, search, () -> rematches.add("x"), () -> null, CLOCK, tm);
    }

    @AfterEach void clear() { MarketContext.clear(); catalog.invalidate(); }

    static ProductGroupDto dto(Long id, String name, List<String> words) {
        return new ProductGroupDto(id, name, words, List.of(), List.of(), List.of(), true, false);
    }

    @Test
    void save_insertUpdateDeleteOrder_thenRematchQueued() {
        ProductGroupsResponse r1 = service.save(new ProductGroupsRequest(List.of(dto(null, "Электрокардиографы", List.of("электрокардиограф")),
                dto(null, "Облучатели", List.of("облучатель", "рециркулятор")))), "vlad");
        assertThat(r1.groups()).extracting(ProductGroupDto::name).containsExactly("Электрокардиографы", "Облучатели");
        Long ecg = r1.groups().get(0).id();
        ProductGroupsResponse r2 = service.save(new ProductGroupsRequest(List.of(dto(null, "Дозаторы", List.of("дозатор")),
                dto(ecg, "ЭКГ", List.of("электрокардиограф")))), "vlad");
        assertThat(r2.groups()).extracting(ProductGroupDto::name).containsExactly("Дозаторы", "ЭКГ");
        assertThat(r2.groups().get(1).id()).as("правка — та же строка, отметки группы живут").isEqualTo(ecg);
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.KZ)).extracting(ProductGroup::getUpdatedBy).containsOnly("vlad");
        assertThat(rematches).hasSize(2);
    }

    @Test
    void invalidGroups_400_nothingSaved() {   // Review Focus #4
        service.save(new ProductGroupsRequest(List.of(dto(null, "ЭКГ", List.of("электрокардиограф")))), "vlad");
        for (ProductGroupDto bad : List.of(dto(null, "Пустая", List.of()), dto(null, "Пробел", List.of("монитор пациента")),
                dto(null, "Повтор", List.of("дозатор", "Дозатор")), dto(null, " ", List.of("дозатор")),
                dto(424242L, "Чужая", List.of("дозатор")))) {
            assertThatThrownBy(() -> service.save(new ProductGroupsRequest(List.of(bad)), "vlad"))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("группа № 1");
        }
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.KZ)).extracting(ProductGroup::getName).containsExactly("ЭКГ");
        assertThat(rematches).hasSize(1);
    }

    @Test
    void rf_readEmpty_write400() {
        MarketContext.set(Market.RF);
        assertThat(service.get().kz()).isFalse();
        assertThatThrownBy(() -> service.save(new ProductGroupsRequest(List.of()), "vlad")).hasMessageContaining("Только для Казахстана");
    }

    @Test
    void probe_reasons() {
        FoundAnnouncement a = new FoundAnnouncement(1L, "1-1", "Закуп", "1", "Больница", BigDecimal.TEN, "2026-10-18 10:00:00",
                "2026-10-21 10:00:00", 220, 2, null, 3);
        FoundAnnouncement old = new FoundAnnouncement(2L, "2-1", "Закуп", "1", "Больница", BigDecimal.TEN, "2026-10-01 10:00:00",
                "2026-10-05 10:00:00", 220, 2, null, 3);
        search.lotsByWord.put("электрод", List.of(
                new FoundLot(1L, "1", "Электрод нейтральный возвратный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "1-1", a),
                new FoundLot(2L, "2", "Электрод сварочный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "1-1", a),
                new FoundLot(3L, "3", "Электрод нейтральный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "2-1", old)));
        ProbeResponse r = service.probe(new ProductGroupDto(null, "Электроды", List.of("электрод"), List.of("нейтральн"),
                List.of("сварочн"), List.of(), true, false));
        assertThat(r.found()).isEqualTo(2);
        assertThat(r.passed()).isEqualTo(1);
        assertThat(r.lots()).extracting(ProbeResponse.Lot::reason).containsExactly("прошёл — по слову «электрод»", "исключение «сварочн»");
    }

    /** «Проверить» у сохранённой группы (с id) — тоже проверка, а не «группа не найдена». */
    @Test
    void probe_existingGroupId_ok() {
        assertThat(service.probe(dto(777L, "Электроды", List.of("электрод"))).found()).isZero();
    }

    /** Новый снимок правил сразу после сохранения (до пересчёта) — без перезапуска. */
    @Test
    void save_refreshesCatalogSnapshot() {
        assertThat(catalog.get().groups()).as("прежний снимок закэширован").isEmpty();
        service.save(new ProductGroupsRequest(List.of(dto(null, "Дозаторы", List.of("дозатор")))), "vlad");
        assertThat(catalog.get().groups()).extracting(GroupRule::name).containsExactly("Дозаторы");
    }

    @Test
    void probe_withoutTokenOrUpstreamDown_readableErrors() {
        search.configured = false;
        assertThatThrownBy(() -> service.probe(dto(null, "Электроды", List.of("электрод"))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Токен goszakup не настроен");
        search.configured = true;
        search.forbidFromCall = 1;
        assertThatThrownBy(() -> service.probe(dto(null, "Электроды", List.of("электрод"))))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("goszakup");
    }
}
