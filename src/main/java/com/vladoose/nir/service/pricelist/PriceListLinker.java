package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceListItem;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Модель, ключ и связь строки прайса (спека §5.4): код модели — из наименования и артикула; связь — только если код ровно у
 * одного нашего товара (KZ, не снят), иначе ровно у одной позиции каталога рынка; кандидатов несколько — без связи.
 * Связь, поставленная человеком, не перезаписывается.
 */
@Component
public class PriceListLinker {

    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;

    public PriceListLinker(SiteProductRepository products, MedEquipmentRepository equipment) {
        this.products = products;
        this.equipment = equipment;
    }

    /** Карты «ключ → id» нашего товара и позиции каталога для серии строк (одна загрузка на прайс). */
    public record Index(Map<String, Set<Long>> products, Map<String, Set<Long>> equipment) {}

    public Index index(Market market) {
        Map<String, Set<Long>> p = new HashMap<>(), e = new HashMap<>();
        if (market == Market.KZ) {
            for (SiteProduct s : products.findByMarketAndRemovedAtIsNull(Market.KZ)) {
                for (String code : PriceListCodes.codes(s.getName())) p.computeIfAbsent(PriceListCodes.key(code), k -> new HashSet<>()).add(s.getId());
            }
        }
        for (MedEquipment m : equipment.findAll()) {               // фильтр рынка — аспектом (сессия привязана)
            if (m.getMarket() != market) continue;
            for (String code : PriceListCodes.codes(m.getName())) e.computeIfAbsent(PriceListCodes.key(code), k -> new HashSet<>()).add(m.getId());
        }
        return new Index(p, e);
    }

    public void link(PriceListItem row, Market market) {
        link(row, index(market));
    }

    public void link(PriceListItem row, Index ix) {
        List<String> codes = new ArrayList<>(PriceListCodes.codes(row.getName()));
        if (row.getArticle() != null) codes.addAll(PriceListCodes.codes(row.getArticle()));
        if (row.getModel() == null && !codes.isEmpty()) row.setModel(PriceTextLines.cut(codes.get(0), 100));
        String key = row.getModel() != null ? PriceListCodes.key(row.getModel()) : null;
        row.setModelKey(key == null ? null : PriceTextLines.cut(key, 100));
        if (key == null || row.getSiteProductId() != null || row.getMedEquipmentId() != null) return;
        Set<Long> p = ix.products().getOrDefault(key, Set.of());
        if (p.size() == 1) {
            row.setSiteProductId(p.iterator().next());
            return;
        }
        if (!p.isEmpty()) return;
        Set<Long> e = ix.equipment().getOrDefault(key, Set.of());
        if (e.size() == 1) row.setMedEquipmentId(e.iterator().next());
    }
}
