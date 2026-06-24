package com.vladoose.nir.market;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level (real servlet + OSIV) проверка scoping по рынку.
 * Этот тест дёргает реальный endpoint /api/facilities с заголовком X-Market и
 * проверяет, что результат отфильтрован по рынку — то есть что Hibernate-фильтр
 * marketFilter включён на той же сессии, которую использует запрос.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketHttpScopingTest {

    private static final String RF_NAME = "ZZHTTP-RF учреждение";
    private static final String KZ_NAME = "ZZHTTP-KZ учреждение";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired FacilityRepository facilityRepository;

    private String sessionCookie;

    @BeforeEach
    void setUp() {
        cleanup();
        facilityRepository.save(Facility.builder().name(RF_NAME).market(Market.RF).build());
        facilityRepository.save(Facility.builder().name(KZ_NAME).market(Market.KZ).build());
        sessionCookie = login("admin", "admin");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        facilityRepository.findAll().stream()
                .filter(f -> f.getName() != null && f.getName().startsWith("ZZHTTP-"))
                .forEach(f -> facilityRepository.deleteById(f.getId()));
    }

    private String login(String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/auth/login", new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("login should succeed: %s", resp.getBody()).isTrue();
        String cookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).as("login must return a session cookie").isNotNull();
        return cookie.split(";", 2)[0];
    }

    @SuppressWarnings("unchecked")
    private List<String> facilityNames(String market) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, sessionCookie);
        h.add("X-Market", market);
        ResponseEntity<List> resp = rest.exchange(
                "http://localhost:" + port + "/api/facilities",
                HttpMethod.GET, new HttpEntity<>(h), List.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /api/facilities should be authorized: %s", resp.getStatusCode()).isTrue();
        return ((List<java.util.Map<String, Object>>) resp.getBody()).stream()
                .map(m -> String.valueOf(m.get("name")))
                .filter(n -> n.startsWith("ZZHTTP-"))
                .toList();
    }

    @Test
    void kzRequestSeesOnlyKzFacilities() {
        List<String> names = facilityNames("KZ");
        assertThat(names).contains(KZ_NAME);
        assertThat(names).doesNotContain(RF_NAME);
    }

    @Test
    void rfRequestSeesOnlyRfFacilities() {
        List<String> names = facilityNames("RF");
        assertThat(names).contains(RF_NAME);
        assertThat(names).doesNotContain(KZ_NAME);
    }
}
