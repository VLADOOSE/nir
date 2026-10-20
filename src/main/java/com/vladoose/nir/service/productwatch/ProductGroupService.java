package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.ProbeResponse;
import com.vladoose.nir.dto.response.ProductGroupDto;
import com.vladoose.nir.dto.response.ProductGroupsResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.util.ErrorText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Страница «Наша продукция» (спека 2026-10-19-product-watch §12.1): весь список групп одним запросом — сначала проверка
 * всего ввода, потом запись своей транзакцией; после её коммита — новый снимок правил и пересчёт отметок в фоне
 * (исполнитель product-watch: читает уже записанные группы). «Проверить» — что площадка находит по словам группы прямо
 * сейчас и что прошло уточнения и исключения.
 */
@Service
public class ProductGroupService {

    static final int NAME_MAX = 120, WORDS_MAX = 10, WORD_MAX = 40, LIST_MAX = 30, GROUPS_MAX = 60, PROBE_SHOWN = 50;

    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final ProductCatalog catalog;
    private final GoszakupSearchClient search;
    private final Runnable rematch;
    private final Supplier<ProductWatchScheduler.Status> watch;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Autowired
    public ProductGroupService(ProductGroupRepository groups, SiteProductRepository products, ProductCatalog catalog,
                               GoszakupSearchClient search, ProductWatchScheduler scheduler, PlatformTransactionManager tm) {
        this(groups, products, catalog, search, scheduler::startRematch, scheduler::status, Clock.systemUTC(), tm);
    }

    public ProductGroupService(ProductGroupRepository groups, SiteProductRepository products, ProductCatalog catalog,
                               GoszakupSearchClient search, Runnable rematch, Supplier<ProductWatchScheduler.Status> watch,
                               Clock clock, PlatformTransactionManager tm) {
        this.groups = groups;
        this.products = products;
        this.catalog = catalog;
        this.search = search;
        this.rematch = rematch;
        this.watch = watch;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public ProductGroupsResponse get() {
        if (MarketContext.get() != Market.KZ) return new ProductGroupsResponse(false, List.of(), null, watch.get());
        List<ProductGroupDto> list = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ).stream().map(ProductGroupService::dto).toList();
        List<SiteProduct> live = products.findByMarketAndRemovedAtIsNull(Market.KZ);
        Map<String, Integer> byBrand = new TreeMap<>();
        for (SiteProduct p : live) byBrand.merge(p.getBrand() == null || p.getBrand().isBlank() ? "—" : p.getBrand(), 1, Integer::sum);
        OffsetDateTime synced = live.stream().map(SiteProduct::getSyncedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        boolean stale = synced == null || synced.isBefore(OffsetDateTime.now(clock).minusDays(2));
        return new ProductGroupsResponse(true, list, new ProductGroupsResponse.Catalog(live.size(), byBrand, synced, stale), watch.get());
    }

    /**
     * Весь список рынка KZ по порядку: чего нет в списке — удаляется (с отметками группы), новое — в конец порядка как
     * пришло. Проверка — до записи; пересчёт — после коммита (иначе фоновый пересчёт мог прочитать прежние группы).
     */
    public ProductGroupsResponse save(ProductGroupsRequest req, String author) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        List<ProductGroupDto> items = req == null || req.groups() == null ? List.of() : req.groups();
        Set<Long> existing = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ).stream().map(ProductGroup::getId)
                .collect(Collectors.toSet());
        check(items, existing);
        tx.executeWithoutResult(st -> write(items, author));
        catalog.refresh();
        rematch.run();
        return get();
    }

    private void write(List<ProductGroupDto> items, String author) {
        Map<Long, ProductGroup> byId = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ).stream()
                .collect(Collectors.toMap(ProductGroup::getId, g -> g));
        Set<Long> keep = items.stream().map(ProductGroupDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (ProductGroup g : byId.values()) if (!keep.contains(g.getId())) groups.delete(g);
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int i = 0; i < items.size(); i++) {
            ProductGroupDto d = items.get(i);
            ProductGroup g = d.id() == null ? ProductGroup.builder().market(Market.KZ).build() : byId.get(d.id());
            // удалили в другой вкладке между проверкой и записью — откат всего списка
            if (g == null) throw new BadRequestException("Группа не найдена — группа № " + (i + 1) + ": обновите страницу");
            g.setName(d.name().strip());
            g.setSearchWords(clean(d.searchWords()));
            g.setRequireWords(clean(d.requireWords()));
            g.setExcludeWords(clean(d.excludeWords()));
            g.setBrandWords(clean(d.brandWords()));
            g.setActive(d.active());
            g.setPosition(i + 1);
            g.setUpdatedAt(now);
            g.setUpdatedBy(author);
            groups.save(g);
        }
        groups.flush();
    }

    /** Весь ввод — до первой записи; текст — с номером группы. */
    private static void check(List<ProductGroupDto> items, Set<Long> existing) {
        if (items.size() > GROUPS_MAX) throw new BadRequestException("Групп не больше " + GROUPS_MAX);
        for (int i = 0; i < items.size(); i++) {
            ProductGroupDto d = items.get(i);
            if (d == null) throw new BadRequestException("Пустая группа — группа № " + (i + 1));
            String where = "группа № " + (i + 1);
            String name = d.name() == null ? "" : d.name().strip();
            if (name.isEmpty()) throw new BadRequestException("Название пустое — " + where);
            if (name.length() > NAME_MAX) throw new BadRequestException("Название длиннее " + NAME_MAX + " символов — " + where);
            if (d.id() != null && !existing.contains(d.id())) throw new BadRequestException("Группа не найдена — " + where + ": обновите страницу");
            List<String> words = clean(d.searchWords());
            if (words.isEmpty()) throw new BadRequestException("Нет слов поиска — " + where + " «" + name + "»");
            if (words.size() > WORDS_MAX) throw new BadRequestException("Слов поиска не больше " + WORDS_MAX + " — " + where);
            Set<String> seen = new HashSet<>();
            for (String w : words) {
                if (w.chars().anyMatch(Character::isWhitespace)) {
                    throw new BadRequestException("Слово поиска — одно слово без пробелов: «" + w + "» — " + where);
                }
                if (w.length() > WORD_MAX) throw new BadRequestException("Слово длиннее " + WORD_MAX + " символов — " + where);
                if (!seen.add(w.toLowerCase(Locale.ROOT))) throw new BadRequestException("Слово «" + w + "» повторяется — " + where);
            }
            for (List<String> list : List.of(clean(d.requireWords()), clean(d.excludeWords()), clean(d.brandWords()))) {
                if (list.size() > LIST_MAX) throw new BadRequestException("Слов в списке не больше " + LIST_MAX + " — " + where);
                for (String w : list) if (w.length() > WORD_MAX) throw new BadRequestException("Слово длиннее " + WORD_MAX + " символов — " + where);
            }
        }
    }

    /**
     * «Проверить» (спека §12.1): лоты с идущим приёмом по словам группы (одна страница на слово, без записи) и причины.
     * Проверяется и несохранённая правка, и существующая группа — id не важен.
     */
    public ProbeResponse probe(ProductGroupDto d) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (d == null) throw new BadRequestException("Нет группы");
        if (!search.isConfigured()) throw new BadRequestException("Токен goszakup не настроен — проверять нечем");
        check(List.of(new ProductGroupDto(null, d.name(), d.searchWords(), d.requireWords(), d.excludeWords(), d.brandWords(),
                d.active(), d.brandGroup())), Set.of());
        ProductGroup g = ProductGroup.builder().id(0L).market(Market.KZ).name(d.name()).searchWords(clean(d.searchWords()))
                .requireWords(clean(d.requireWords())).excludeWords(clean(d.excludeWords())).brandWords(clean(d.brandWords()))
                .active(true).brandGroup(d.brandGroup()).build();
        GroupRule rule = GroupRule.of(g);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        LocalDate today = now.toLocalDate();
        Map<Long, FoundLot> lots = new LinkedHashMap<>();
        Map<Long, String> foundBy = new HashMap<>();
        for (String w : rule.searchWords()) {
            List<FoundLot> page;
            try {
                page = search.searchLots(w, ProductWatchService.OPEN_LOT, today.minusDays(60), today.plusDays(1), null).items();
            } catch (BadRequestException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new UpstreamException("goszakup не ответил — проверить не удалось: " + ErrorText.of(e));
            }
            for (FoundLot l : page) {
                LocalDateTime at = l.announcement() == null ? null : ImportDeadline.parse(l.announcement().endDate());
                if (at != null && at.isAfter(now) && l.id() != null && lots.putIfAbsent(l.id(), l) == null) foundBy.put(l.id(), w);
            }
        }
        List<ProbeResponse.Lot> out = new ArrayList<>();
        int passed = 0;
        for (FoundLot l : lots.values()) {
            String lotText = ProductText.normalize(String.join(" ", Objects.toString(l.nameRu(), ""), Objects.toString(l.descriptionRu(), "")));
            String ann = ProductText.normalize(l.announcement().nameRu());
            String why;
            String word;
            if (rule.brandGroup()) {   // слова группы — наши бренды и модели: площадка нашла по ним — значит назван наш
                why = null;
                word = foundBy.get(l.id());
            } else {
                why = rule.rejectReason(lotText, ann, false);
                word = rule.typeWord(lotText, ann, false);
            }
            if (why == null) passed++;
            out.add(new ProbeResponse.Lot(l.trdBuyNumberAnno(), l.nameRu(), l.announcement().nameRu(), l.customerNameRu(),
                    l.announcement().endDate(), why == null, why == null ? "прошёл — по слову «" + word + "»" : why));
        }
        out.sort(Comparator.comparing((ProbeResponse.Lot x) -> !x.passed()));
        return new ProbeResponse(lots.size(), passed, out.stream().limit(PROBE_SHOWN).toList());
    }

    private static List<String> clean(List<String> xs) {
        return xs == null ? new ArrayList<>() : xs.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static ProductGroupDto dto(ProductGroup g) {
        return new ProductGroupDto(g.getId(), g.getName(), g.getSearchWords(), g.getRequireWords(), g.getExcludeWords(),
                g.getBrandWords(), g.isActive(), g.isBrandGroup());
    }
}
