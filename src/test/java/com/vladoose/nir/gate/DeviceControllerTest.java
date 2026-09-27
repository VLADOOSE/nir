package com.vladoose.nir.gate;

import com.vladoose.nir.controller.DeviceController;
import com.vladoose.nir.dto.request.DeviceApproveRequest;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DeviceControllerTest {

    @Autowired DeviceController controller;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private Long waiting(String name) {
        DeviceGateService.GateResult r = gate.request(null, name, null, "5.6.7.8", OffsetDateTime.now());
        return repo.findByTokenHash(DeviceTokens.hash(r.newToken())).orElseThrow().getId();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotSeeOrDecide() {
        Long id = waiting("Асель");
        assertThatThrownBy(() -> controller.list(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.pendingCount()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.approve(id, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.revoke(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminApprovesWithLabelAndSeesCount() {
        long before = controller.pendingCount().get("count");
        Long id = waiting("Асель");
        assertThat(controller.pendingCount().get("count")).isEqualTo(before + 1);

        DeviceApproveRequest body = new DeviceApproveRequest();
        body.setLabel("Телефон Асель");
        DeviceResponse d = controller.approve(id, body);

        assertThat(d.getStatus()).isEqualTo("TRUSTED");
        assertThat(d.getLabel()).isEqualTo("Телефон Асель");
        assertThat(d.getDecidedBy()).isEqualTo("admin1");
        assertThat(controller.pendingCount().get("count")).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminRejectsAndRevokes() {
        assertThat(controller.reject(waiting("Чужой")).getStatus()).isEqualTo("REJECTED");

        Long id = waiting("Асель");
        controller.approve(id, null);
        assertThat(controller.revoke(id).getStatus()).isEqualTo("REVOKED");
    }
}
