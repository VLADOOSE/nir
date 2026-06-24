package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
import com.vladoose.nir.entity.MedRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MedRegistryRepositoryTest {

    @Autowired
    MedRegistryRepository repository;

    private MedRegistry row(String reg, String name, String producer) {
        return MedRegistry.builder()
                .regNumber(reg).name(name).producer(producer)
                .country("КАЗАХСТАН").unlimited(true)
                .build();
    }

    @Test
    void findCandidates_ranksBestMatchFirstAndPopulatesScore() {
        // Уникальные токены, чтобы тест не пересекался с реальными 14k записями
        repository.save(row("ZZTEST-001", "Аппарат УЗИ ТЕСТУНИКУМ", "ZZTESTVENDOR-QWE Ltd"));
        repository.save(row("ZZTEST-002", "Прибор посторонний ЯБЛОКО",   "ZZTESTVENDOR-QWE Ltd"));
        repository.save(row("ZZTEST-003", "Аппарат УЗИ ТЕСТУНИКУМ", "Совсем Другой ВендорZZQ"));
        repository.flush();

        List<RegistryCandidateRow> result =
                repository.findCandidates("Аппарат УЗИ ТЕСТУНИКУМ", "ZZTESTVENDOR-QWE Ltd", 5);

        assertThat(result).isNotEmpty();
        // Лучший матч: совпали и производитель (вес 0.6), и наименование (вес 0.4)
        assertThat(result.get(0).getRegNumber()).isEqualTo("ZZTEST-001");
        assertThat(result.get(0).getScore()).isNotNull();
        assertThat(result.get(0).getScore()).isGreaterThan(0.0);
        // Все три наши записи попали в выдачу
        assertThat(result).extracting(RegistryCandidateRow::getRegNumber)
                .contains("ZZTEST-001", "ZZTEST-002", "ZZTEST-003");
    }

    @Test
    void findCandidates_shortBrandMatchesLongProducerName_viaWordSimilarity() {
        repository.save(row("ZZWS-001", "Электрокардиограф BeneHeart R12",
                "Shenzhen ZZBrandUniq Bio-Medical Electronics Co., Ltd."));
        repository.flush();
        // короткий бренд как слово внутри длинного производителя
        List<RegistryCandidateRow> result =
                repository.findCandidates("Электрокардиограф", "ZZBrandUniq", 5);
        assertThat(result).extracting(RegistryCandidateRow::getRegNumber).contains("ZZWS-001");
    }

    @Test
    void findByRegNumber_returnsRow() {
        repository.save(row("ZZTEST-FIND", "Тест-наименование", "Тест-производитель"));
        repository.flush();
        assertThat(repository.findByRegNumber("ZZTEST-FIND")).isPresent();
        assertThat(repository.findByRegNumber("НЕТ-ТАКОГО")).isEmpty();
    }
}
