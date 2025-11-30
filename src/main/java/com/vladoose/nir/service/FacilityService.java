package com.vladoose.nir.service;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.repository.FacilityRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class FacilityService {
    private final FacilityRepository repo;
    public FacilityService(FacilityRepository repo){ this.repo = repo; }

    public List<Facility> list(){ return repo.findAll(); }
    public Facility create(Facility f){ f.setId(null); return repo.save(f); }
    public Facility update(Long id, Facility f){ f.setId(id); return repo.save(f); }
    public void delete(Long id){ repo.deleteById(id); }
}
