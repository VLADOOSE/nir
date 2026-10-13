package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Поиск по реестру по наименованию (спека 2026-10-13 §2.5, решение плана 5). */
@SpringBootTest
@Transactional
class RegistrySearchByNameTest {

    @Autowired MedRegistryRepository registry;
    @Autowired RegistryMatchService match;

    private void rec(String no, String name, String producer) {
        registry.save(MedRegistry.builder().regNumber(no).name(name).producer(producer).country("КАЗАХСТАН").unlimited(true).build());
    }

    private static List<String> numbers(List<RegistryCandidateResponse> r) {
        return r.stream().map(RegistryCandidateResponse::getRegNumber).toList();
    }

    @Test
    void wordsInAnyOrder_eachMustBeInName() {
        rec("ZZ-RSN-1", "Рециркулятор воздуха бактерицидный ZZRSNMARK передвижной", "ZZRSN Завод");
        rec("ZZ-RSN-2", "Облучатель-рециркулятор передвижной ZZRSNMARK", "ZZRSN Завод");
        rec("ZZ-RSN-3", "Рециркулятор настенный ZZRSNMARK", "ZZRSN Завод");
        registry.flush();

        List<String> found = numbers(match.search("передвижной zzrsnmark рециркулятор", 20));

        assertThat(found).contains("ZZ-RSN-1", "ZZ-RSN-2").doesNotContain("ZZ-RSN-3");
    }

    @Test
    void equalScoreOrderedByRegNumber() {
        rec("ZZ-RSN-B", "Тонометр ZZRSNTIE механический", "ZZRSN Завод");
        rec("ZZ-RSN-A", "Тонометр ZZRSNTIE механический", "ZZRSN Завод");
        registry.flush();

        List<String> found = numbers(match.search("тонометр zzrsntie", 20));

        assertThat(found.indexOf("ZZ-RSN-A")).isGreaterThanOrEqualTo(0).isLessThan(found.indexOf("ZZ-RSN-B"));
    }

    @Test
    void producerFindsRecord() {
        rec("ZZ-RSN-P", "Монитор пациента", "ZZRSNVENDOR GmbH");
        registry.flush();

        assertThat(numbers(match.search("ZZRSNVENDOR", 20))).contains("ZZ-RSN-P");
    }

    /** Review Focus 2: спецсимволы, одни короткие слова, длинный запрос — пусто или обрезка, не 500. */
    @Test
    void oddQueriesDoNotFail() {
        assertThat(match.search("а", 20)).isEmpty();
        assertThat(match.search("100% _x_ | «ZZ»", 20)).isNotNull();
        assertThat(match.search("ab cd", 20)).isNotNull();
        assertThat(match.search("рециркулятор ".repeat(40), 20)).isNotNull();
    }

    /** Критерий спеки на живом снимке реестра (14 072): прежний поиск давал одну запись. */
    @Test
    void liveSnapshot_recirculatorMobile_moreThanOne() {
        assertThat(match.search("рециркулятор передвижной", 20)).hasSizeGreaterThan(1);
    }

    @Test
    void tokens_longestFirst_upToFive_shortDropped() {
        assertThat(RegistryMatchService.searchTokens("Дефибриллятор ДКИ-Н-12 с принадлежностями"))
                .containsExactly("принадлежностями", "дефибриллятор", "дки-н-12");
        assertThat(RegistryMatchService.searchTokens("ab cd")).containsExactly("ab cd");
        assertThat(RegistryMatchService.searchTokens("a|b%c")).doesNotContain("|");
    }
}
