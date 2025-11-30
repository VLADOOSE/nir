package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.TenderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/tender")
public class TenderController {
    private final TenderService service;
    public TenderController(TenderService service) { this.service = service; }
    @GetMapping
    public List<Tender> all() { return service.findAll(); }
    @PostMapping
    public ResponseEntity<Tender> create(@RequestBody Tender t) { Tender created = service.create(t); return ResponseEntity.created(URI.create("/api/tender/" + created.getId())).body(created); }
}

