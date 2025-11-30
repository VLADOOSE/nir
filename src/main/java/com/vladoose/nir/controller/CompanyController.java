package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Company;
import com.vladoose.nir.service.CompanyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyService service;
    public CompanyController(CompanyService service) { this.service = service; }
    @GetMapping
    public List<Company> all() { return service.findAll(); }
    @PostMapping
    public ResponseEntity<Company> create(@RequestBody Company c) { Company created = service.create(c); return ResponseEntity.created(URI.create("/api/company/" + created.getCompanyId())).body(created); }
}