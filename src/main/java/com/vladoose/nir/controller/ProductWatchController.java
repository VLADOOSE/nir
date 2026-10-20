package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.ProbeResponse;
import com.vladoose.nir.dto.response.ProductCatalogResponse;
import com.vladoose.nir.dto.response.ProductGroupDto;
import com.vladoose.nir.dto.response.ProductGroupsResponse;
import com.vladoose.nir.service.productwatch.ProductCatalogQuery;
import com.vladoose.nir.service.productwatch.ProductGroupService;
import com.vladoose.nir.service.productwatch.ProductWatchScheduler;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * «Наша продукция» (спека 2026-10-19-product-watch §12.1, §13): группы, «Проверить», поиск по стране, каталог сайта.
 * «Искать сейчас» и «Обновить» каталог работают только при включённом блоке (PRODUCT_WATCH_ENABLED) — иначе ответ —
 * статус с enabled = false.
 */
@RestController
public class ProductWatchController {

    private final ProductGroupService groups;
    private final ProductWatchScheduler scheduler;
    private final ProductCatalogQuery catalogQuery;

    public ProductWatchController(ProductGroupService groups, ProductWatchScheduler scheduler, ProductCatalogQuery catalogQuery) {
        this.groups = groups;
        this.scheduler = scheduler;
        this.catalogQuery = catalogQuery;
    }

    /** Вкладка «Наша продукция → Товары» (спека 2026-10-20-product-spec-fit §8.2): товары сайта с РУ; на РФ — пусто. */
    @GetMapping("/api/product-catalog")
    public ProductCatalogResponse catalog() {
        return catalogQuery.list();
    }

    @GetMapping("/api/product-groups")
    public ProductGroupsResponse get() {
        return groups.get();
    }

    @PutMapping("/api/product-groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductGroupsResponse save(@RequestBody ProductGroupsRequest req) {
        return groups.save(req, CurrentUser.name());
    }

    @PostMapping("/api/product-groups/probe")
    @PreAuthorize("hasRole('ADMIN')")
    public ProbeResponse probe(@RequestBody ProductGroupDto group) {
        return groups.probe(group);
    }

    @PostMapping("/api/product-watch/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status run() {
        return scheduler.startSearch();
    }

    @GetMapping("/api/product-watch/status")
    public ProductWatchScheduler.Status status() {
        return scheduler.status();
    }

    @PostMapping("/api/site-catalog/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status syncCatalog() {
        return scheduler.startCatalog();
    }
}
