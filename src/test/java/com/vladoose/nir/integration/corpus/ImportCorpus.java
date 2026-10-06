package com.vladoose.nir.integration.corpus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Замороженный корпус импорта (снят {@code ImportCorpusSnapshot}): JSON Lines в gzip из test-ресурсов. */
public final class ImportCorpus {

    public record CorpusLot(String code, String name, String description) {}

    public record CorpusTender(String platform, String anno, String name, Integer lotsCount, List<CorpusLot> lots) {
        public CorpusTender {
            lots = lots == null ? List.of() : List.copyOf(lots);
        }
    }

    private static final ObjectMapper OM = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ImportCorpus() {}

    /** @param resource путь ресурса, например {@code /import-corpus/goszakup-zko.jsonl.gz} */
    public static List<CorpusTender> load(String resource) {
        InputStream raw = ImportCorpus.class.getResourceAsStream(resource);
        if (raw == null) throw new IllegalArgumentException("нет ресурса " + resource);
        List<CorpusTender> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) out.add(OM.readValue(line, CorpusTender.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
