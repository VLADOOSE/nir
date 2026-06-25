package com.vladoose.nir.imports;

import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class HeaderSynonymTest {

    @Autowired HeaderSynonymRepository repository;

    @Test
    void persistsAndFindsByHeaderNorm() {
        repository.save(HeaderSynonym.builder().headerNorm("zzтест-колонка").field(LineField.MANUFACT).build());
        repository.flush();
        assertThat(repository.findByHeaderNorm("zzтест-колонка"))
                .get().extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }

    @Test
    void seedLoaded() {
        assertThat(repository.findByHeaderNorm("производитель"))
                .get().extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }
}
