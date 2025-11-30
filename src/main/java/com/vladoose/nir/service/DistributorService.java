package com.vladoose.nir.service;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.repository.DistributorRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DistributorService {
    private final DistributorRepository repo;
    public DistributorService(DistributorRepository repo){ this.repo = repo; }
    public List<Distributor> list(){ return repo.findAll(); }
    public Distributor create(Distributor d){ d.setId(null); return repo.save(d); }
    public Distributor update(Long id, Distributor d){ d.setId(id); return repo.save(d); }
    public void delete(Long id){ repo.deleteById(id); }
}
