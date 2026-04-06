package com.vladoose.nir.service;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserAccountRepository repository;

    public UserService(UserAccountRepository repository) {
        this.repository = repository;
    }

    public List<UserAccount> findAll() {
        return repository.findAll();
    }

    public UserAccount findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден: id=" + id));
    }

    @Transactional
    public UserAccount save(UserAccount user) {
        return repository.save(user);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
