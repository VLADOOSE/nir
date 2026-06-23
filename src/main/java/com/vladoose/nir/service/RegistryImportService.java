package com.vladoose.nir.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

@Service
public class RegistryImportService {

    private static final String UPSERT_SQL =
            "INSERT INTO med_registry (reg_number, name, producer, country, reg_date, expiration_date, unlimited, imported_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, now()) " +
            "ON CONFLICT (reg_number) DO UPDATE SET " +
            "name = EXCLUDED.name, producer = EXCLUDED.producer, country = EXCLUDED.country, " +
            "reg_date = EXCLUDED.reg_date, expiration_date = EXCLUDED.expiration_date, " +
            "unlimited = EXCLUDED.unlimited, imported_at = now()";

    private final MedRegistryRepository registryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String dumpLocation;

    public RegistryImportService(MedRegistryRepository registryRepository,
                                 JdbcTemplate jdbcTemplate,
                                 ResourceLoader resourceLoader,
                                 ObjectMapper objectMapper,
                                 @Value("${registry.kz.dump-location:classpath:registry/rk-mi-registry-full.json}") String dumpLocation) {
        this.registryRepository = registryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.dumpLocation = dumpLocation;
    }

    @Transactional
    public int importFromDump() {
        Resource resource = resourceLoader.getResource(dumpLocation);
        List<RegistryDumpRecord> records;
        try (InputStream is = resource.getInputStream()) {
            records = objectMapper.readValue(is, new TypeReference<List<RegistryDumpRecord>>() {});
        } catch (IOException e) {
            throw new BadRequestException("Не удалось прочитать дамп реестра (" + dumpLocation + "): " + e.getMessage());
        }
        List<RegistryDumpRecord> valid = records.stream()
                .filter(r -> r.getReg() != null && !r.getReg().isBlank())
                .toList();

        jdbcTemplate.batchUpdate(UPSERT_SQL, valid, 500, (ps, r) -> {
            ps.setString(1, r.getReg());
            ps.setString(2, r.getName());
            ps.setString(3, r.getProducer());
            ps.setString(4, r.getCountry());
            ps.setObject(5, parseDate(r.getRegDate()));
            ps.setObject(6, parseDate(r.getExp()));
            ps.setObject(7, r.getUnlimited() != null ? r.getUnlimited() : Boolean.FALSE);
        });
        return valid.size();
    }

    @Transactional
    public int importIfEmpty() {
        if (registryRepository.count() > 0) {
            return 0;
        }
        return importFromDump();
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso.substring(0, 10)); // "2026-06-17T00:00:00" -> 2026-06-17
    }
}
