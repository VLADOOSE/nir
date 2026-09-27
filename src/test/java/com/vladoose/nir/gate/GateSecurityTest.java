package com.vladoose.nir.gate;

import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-правила калитки: прямые вызовы контроллера фильтры Spring Security не проходят.
 * MockMvc собирается из ОБЩЕГО тестового контекста, а не через @AutoConfigureMockMvc: та аннотация меняет ключ
 * контекста — Spring поднимает ещё один со своим пулом Hikari (10 соединений), и набор упирается в
 * max_connections=100 nirdb («too many clients» в чужих тестовых классах).
 */
@SpringBootTest
@Transactional
class GateSecurityTest {

    @Autowired WebApplicationContext wac;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void gateIsReachableWithoutLoggingIn() throws Exception {
        DeviceGateService.GateResult r = gate.request(null, "Асель", null, null, OffsetDateTime.now());
        Long id = repo.findByTokenHash(DeviceTokens.hash(r.newToken())).orElseThrow().getId();
        gate.approve(id, null, "admin1", OffsetDateTime.now());

        // 204 без входа в АИС: будь /api/gate/check закрыт, nginx получал бы 401 от Spring Security и не пускал никого
        mvc.perform(get("/api/gate/check").cookie(new Cookie("ais_device", r.newToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/gate/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"));
        mvc.perform(post("/api/gate/request").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Марат\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("ais_device"))
                .andExpect(cookie().httpOnly("ais_device", true))
                .andExpect(cookie().secure("ais_device", true))
                .andExpect(cookie().sameSite("ais_device", "Lax"))
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void blankNameIsBadRequest() throws Exception {
        mvc.perform(post("/api/gate/request").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void devicesNeedLogin() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void devicesNeedAdmin() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isForbidden());
    }
}
