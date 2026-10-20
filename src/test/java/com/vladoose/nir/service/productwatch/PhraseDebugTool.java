package com.vladoose.nir.service.productwatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.util.*;

/** Какие фразы каталога совпали в ТЗ (задача 6, отладка калибровки). PRODUCT_SPEC_CALIBRATION=1 PHRASE_DEBUG_FILES=a,b */
@EnabledIfEnvironmentVariable(named = "PRODUCT_SPEC_CALIBRATION", matches = "1")
class PhraseDebugTool {

    @Test
    void dump() throws Exception {
        var groups = com.vladoose.nir.productwatch.ProductFixtures.seedGroups();
        var catalog = com.vladoose.nir.productwatch.ProductFixtures.catalogFull();
        CatalogSnapshot snap = CatalogSnapshot.build(groups, catalog, new CatalogPhrases.Rules(true, 2, 3, 4, 0.05, 1, true, 0));
        for (String f : System.getenv("PHRASE_DEBUG_FILES").split(",")) {
            String spec = com.vladoose.nir.productwatch.ProductFixtures.spec(f);
            List<String> sp = ProductText.words(ProductText.normalize(spec)).stream().map(ProductText::prefix).toList();
            var idx = CatalogPhrases.index(sp);
            for (GroupRule r : snap.groups()) {
                Set<String> own = ProductMatcher.ownWords(r);
                for (CatalogProduct p : snap.groupProducts(r.id())) {
                    for (CatalogPhrases.Phrase x : snap.phrases().getOrDefault(p.id(), List.of())) {
                        if (x.rare().stream().allMatch(own::contains)) continue;
                        if (CatalogPhrases.occurs(x.prefixes(), sp, idx))
                            System.out.println("PHDBG " + f.substring(0, 10) + " g=" + r.id() + " " + p.brand() + " | " + String.join(" ", x.prefixes())
                                    + " | rare=" + x.rare() + " | seg=" + x.text());
                    }
                }
            }
        }
    }
}
