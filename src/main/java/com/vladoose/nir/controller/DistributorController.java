package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.service.DistributorService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/distributors")
@CrossOrigin(origins = "http://localhost:5173")
public class DistributorController {
    private final DistributorService svc;
    public DistributorController(DistributorService svc){ this.svc = svc; }

    @GetMapping
    public List<Distributor> all(){ return svc.list(); }

    @PostMapping
    public Distributor create(@RequestBody Distributor d){ return svc.create(d); }

    @PutMapping("/{id}")
    public Distributor update(@PathVariable Long id, @RequestBody Distributor d){ return svc.update(id,d); }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id){ svc.delete(id); }
}
