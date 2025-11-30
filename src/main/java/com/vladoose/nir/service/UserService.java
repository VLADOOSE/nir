package com.vladoose.nir.service;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {
    private final UserAccountRepository repo;
    public UserService(UserAccountRepository repo){ this.repo = repo; }
    public List<UserAccount> list(){ return repo.findAll(); }
}
