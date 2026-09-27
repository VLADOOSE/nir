package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.GateAccessRequest;
import com.vladoose.nir.dto.response.GateStateResponse;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.ClientIp;
import com.vladoose.nir.util.GateCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/**
 * Калитка ais.westmed.kz (спека device-gate §7): открыта без входа в АИС — её зовёт nginx хоста
 * (подзапрос auth_request) и страница /gate/ недопущенного устройства.
 */
@RestController
@RequestMapping("/api/gate")
public class GateController {

    private final DeviceGateService gate;

    public GateController(DeviceGateService gate) {
        this.gate = gate;
    }

    /** Подзапрос nginx на каждый запрос к ais.westmed.kz: 204 — пускать, 401 — на калитку. Без БД. */
    @GetMapping("/check")
    public ResponseEntity<Void> check(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        HttpStatus s = gate.isTrusted(token, OffsetDateTime.now()) ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/request")
    public ResponseEntity<GateStateResponse> request(@CookieValue(name = GateCookie.NAME, required = false) String token,
                                                     @RequestBody(required = false) GateAccessRequest body,
                                                     @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                                     HttpServletRequest http) {
        DeviceGateService.GateResult r = gate.request(token, body == null ? null : body.getName(), userAgent,
                ClientIp.of(http), OffsetDateTime.now());
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (r.newToken() != null) b.header(HttpHeaders.SET_COOKIE, GateCookie.of(r.newToken()).toString());
        return b.body(toResponse(r));
    }

    @GetMapping("/status")
    public ResponseEntity<GateStateResponse> status(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toResponse(gate.status(token, OffsetDateTime.now())));
    }

    private static GateStateResponse toResponse(DeviceGateService.GateResult r) {
        return new GateStateResponse(r.state().name(), r.code(), r.expiresAt());
    }
}
