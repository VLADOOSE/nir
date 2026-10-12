package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.service.SiteProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/site-products")
public class SiteProductController {

    private final SiteProductService service;

    public SiteProductController(SiteProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<SiteProductResponse> search(@RequestParam(defaultValue = "") String q) {
        return service.search(q);
    }
}
