package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.service.ClientMailSendService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §8). Только администратор. */
@RestController
@RequestMapping("/api/client-mail")
public class ClientMailController {

    private final ClientMailComposeService compose;
    private final ClientMailSendService send;

    public ClientMailController(ClientMailComposeService compose, ClientMailSendService send) {
        this.compose = compose;
        this.send = send;
    }

    @GetMapping("/compose")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMailComposeResponse compose(@RequestParam(required = false) Long chatId,
                                             @RequestParam(required = false) Long leadId,
                                             @RequestParam(required = false) Long tenderId,
                                             @RequestParam(required = false) Long offerId) {
        return compose.compose(chatId, leadId, tenderId, offerId, CurrentUser.name());
    }

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMailSendResponse send(@RequestPart("letter") ClientMailSendRequest letter,
                                       @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return send.send(letter, files == null ? List.of() : files, CurrentUser.name());
    }
}
