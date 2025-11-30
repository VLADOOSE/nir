package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.service.FacilityService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/facilities")
@CrossOrigin(origins = "http://localhost:5173")
public class FacilityController {
    private final FacilityService svc;
    public FacilityController(FacilityService svc){ this.svc = svc; }

    @GetMapping
    public List<Facility> all(){ return svc.list(); }

    @PostMapping
    public Facility create(@RequestBody Facility f){ return svc.create(f); }

    @PutMapping("/{id}")
    public Facility update(@PathVariable Long id, @RequestBody Facility f){ return svc.update(id,f); }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id){ svc.delete(id); }
}
