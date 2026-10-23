package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListReader;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Разбор настоящих прайсов (спека §12): репозиторий публичный — файлы не кладутся, прогон локально:
 * PRICE_LIST_CORPUS=~/Downloads/прайсы ./gradlew test --tests '*PriceListCorpusTool*' -i. Печатает шапку и строки — сверять глазами.
 */
@EnabledIfEnvironmentVariable(named = "PRICE_LIST_CORPUS", matches = ".+")
class PriceListCorpusTool {

    @Test
    void print() throws Exception {
        try (Stream<Path> files = Files.list(Paths.get(System.getenv("PRICE_LIST_CORPUS")))) {
            for (Path f : files.sorted().toList()) {
                byte[] b = Files.readAllBytes(f);
                String name = f.getFileName().toString();
                ReplyFileKind kind = SupplierReplyFiles.kind(name, null, b);
                ParsedPriceList p = PriceListReader.read(name, kind, b, Map.of());
                System.out.println("=== " + name + " · " + kind + " · " + p.status() + (p.note() == null ? "" : " — " + p.note()));
                System.out.println("валюта " + p.currency() + " · колонки " + p.columns().stream().map(PriceListColumn::header).toList()
                        + " · наша " + p.ourColumn() + " · с " + p.listDate() + " по " + p.validTo() + " · розница " + p.retail());
                System.out.println("условия: " + p.terms());
                for (ParsedPriceRow r : p.rows()) {
                    System.out.println("  [" + r.section() + "] " + r.name() + " · " + r.prices() + " · РУ " + r.regText());
                }
            }
        }
    }
}
