package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Справочник площадки (спека §8): 30 дней у себя, «не найден» запоминается, сбой — что есть, стоп-коды — наверх. */
@SpringBootTest
@Transactional
class GoszakupSubjectsTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @Autowired GoszakupSubjectRepository repo;
    FakeGoszakupResultsClient fake;
    GoszakupSubjects subjects;

    @BeforeEach
    void setUp() {
        repo.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        subjects = new GoszakupSubjects(fake, repo, CLOCK, ms -> { });
    }

    private void cached(String bin, String name, String phone, OffsetDateTime at) {
        repo.saveAndFlush(GoszakupSubject.builder().bin(bin).nameRu(name).phone(phone).fetchedAt(at).build());
    }

    @Test
    void freshCache_noCall() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(1));
        assertThat(subjects.get("110240019426").phone()).isEqualTo("31398");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void stale_refetched() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(40));
        fake.subjects.put("110240019426", new SubjectInfo("110240019426", "ГКП «Больница»", "8 (71133) 31398", "a@b.kz"));
        SubjectInfo s = subjects.get("110240019426");
        assertThat(s.phone()).isEqualTo("8 (71133) 31398");
        assertThat(repo.findById("110240019426").orElseThrow().getEmail()).isEqualTo("a@b.kz");
    }

    @Test
    void notFound_remembered() {
        assertThat(subjects.get("999999999999")).isNull();
        assertThat(repo.findById("999999999999").orElseThrow().isNotFound()).isTrue();
        assertThat(subjects.get("999999999999")).isNull();
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void failure_returnsCached_stopCodeThrows() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(40));
        fake.subjectError = new GoszakupCallException("goszakup API 500", 500, false, null);
        assertThat(subjects.get("110240019426").phone()).isEqualTo("31398");
        fake.subjectError = null;
        fake.forbidFromCall = 1;
        assertThatThrownBy(() -> subjects.get("110240019427")).isInstanceOf(GoszakupCallException.class);
    }

    @Test
    void names_limitAndInvalid() {
        cached("100000000001", "ТОО Один", null, NOW);
        fake.subjects.put("100000000002", new SubjectInfo("100000000002", "ТОО Два", null, null));
        fake.subjects.put("100000000003", new SubjectInfo("100000000003", "ТОО Три", null, null));
        Map<String, String> names = subjects.names(List.of("100000000001", "100000000002", "100000000003", "123"), 1);
        assertThat(names).containsEntry("100000000001", "ТОО Один").containsEntry("100000000002", "ТОО Два").hasSize(2);
        assertThat(fake.calls).containsExactly("subject:100000000002");
        assertThat(subjects.get("123")).isNull();
    }
}
