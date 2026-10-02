package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.CompanyProfileRequest;
import com.vladoose.nir.dto.response.CompanyProfileResponse;
import com.vladoose.nir.service.CompanyImageKind;
import com.vladoose.nir.service.CompanyProfileService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** «Система → Реквизиты и печать» (спека §7, §10). Чтение профиля — любому вошедшему, остальное — ADMIN. */
@RestController
@RequestMapping("/api/company-profile")
public class CompanyProfileController {

    private final CompanyProfileService service;

    public CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    @GetMapping
    public CompanyProfileResponse get() {
        return CompanyProfileResponse.of(service.current());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse put(@Valid @RequestBody CompanyProfileRequest req) {
        return CompanyProfileResponse.of(service.update(req));
    }

    @PostMapping(value = "/images/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse upload(@PathVariable String kind, @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "true") boolean removeBackground) throws IOException {
        return CompanyProfileResponse.of(service.putImage(CompanyImageKind.fromPath(kind), file.getBytes(), removeBackground));
    }

    /** Печать и подпись — только администратору; в документы вставляются на сервере. */
    @GetMapping("/images/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> image(@PathVariable String kind) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(service.image(CompanyImageKind.fromPath(kind)));
    }

    @DeleteMapping("/images/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse deleteImage(@PathVariable String kind) {
        return CompanyProfileResponse.of(service.deleteImage(CompanyImageKind.fromPath(kind)));
    }
}
