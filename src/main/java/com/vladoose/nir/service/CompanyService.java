package com.vladoose.nir.service;

import com.vladoose.nir.entity.Company;
import com.vladoose.nir.repository.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional
public class CompanyService {
    private final CompanyRepository repo;
    public CompanyService(CompanyRepository repo) { this.repo = repo; }
    public List<Company> findAll() { return repo.findAll(); }
    public Company create(Company c) { c.setCompanyId(null); return repo.save(c); }
}
