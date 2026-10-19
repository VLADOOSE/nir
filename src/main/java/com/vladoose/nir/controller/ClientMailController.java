package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §8). Только администратор. */
@RestController
@RequestMapping("/api/client-mail")
public class ClientMailController {

    private final ClientMailComposeService compose;

    public ClientMailController(ClientMailComposeService compose) {
        this.compose = compose;
    }

    @GetMapping("/compose")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMailComposeResponse compose(@RequestParam(required = false) Long chatId,
                                             @RequestParam(required = false) Long leadId,
                                             @RequestParam(required = false) Long tenderId,
                                             @RequestParam(required = false) Long offerId) {
        return compose.compose(chatId, leadId, tenderId, offerId, CurrentUser.name());
    }
}
