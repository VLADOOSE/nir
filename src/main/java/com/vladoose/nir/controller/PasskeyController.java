package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.PasskeyListResponse;
import com.vladoose.nir.service.PasskeyService;
import com.vladoose.nir.util.UserAgentSummary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** «Мой профиль»: свои ключи входа — любому вошедшему, только свои (спека passkeys-login §6). */
@RestController
@RequestMapping("/api/passkeys")
public class PasskeyController {

    private final PasskeyService passkeys;

    public PasskeyController(PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @GetMapping
    public PasskeyListResponse list(@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return new PasskeyListResponse(passkeys.list(currentUser()), UserAgentSummary.describe(userAgent));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        passkeys.delete(currentUser(), id);
    }

    // после входа по ключу принципал — PublicKeyCredentialUserEntity, а не UserDetails: только getName()
    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
