package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "registry.kz.dump-location=classpath:registry/test-dump.json")
class RegistryImportServiceTest {

    @Autowired RegistryImportService importService;
    @Autowired MedRegistryRepository repository;

    @Test
    void importFromDump_insertsRecordsAndParsesFields() {
        int n = importService.importFromDump();
        assertThat(n).isEqualTo(3);

        MedRegistry a = repository.findByRegNumber("ZZIMP-001").orElseThrow();
        assertThat(a.getName()).isEqualTo("Тест Аппарат А");
        assertThat(a.getProducer()).isEqualTo("Импорт-Вендор-1");
        assertThat(a.getRegDate()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(a.getExpirationDate()).isEqualTo(LocalDate.of(2030, 1, 10));
        assertThat(a.getUnlimited()).isFalse();

        MedRegistry b = repository.findByRegNumber("ZZIMP-002").orElseThrow();
        assertThat(b.getExpirationDate()).isNull();
        assertThat(b.getUnlimited()).isTrue();
    }

    @Test
    void importFromDump_isIdempotentByRegNumber() {
        importService.importFromDump();
        importService.importFromDump(); // повторный прогон не должен плодить дубли
        long count = repository.findAll().stream()
                .filter(r -> r.getRegNumber().startsWith("ZZIMP-")).count();
        assertThat(count).isEqualTo(3);
    }
}
