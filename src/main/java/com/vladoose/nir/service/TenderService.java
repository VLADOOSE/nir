package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional
public class TenderService {
    private final TenderRepository repo;
    public TenderService(TenderRepository repo) { this.repo = repo; }
    public List<Tender> findAll() { return repo.findAll(); }
    public Tender create(Tender t) { t.setId(null); return repo.save(t); }
}