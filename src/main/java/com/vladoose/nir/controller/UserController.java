package com.vladoose.nir.controller;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserAccount> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public UserAccount findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public UserAccount create(@Valid @RequestBody UserAccount user) {
        return service.save(user);
    }

    @PutMapping("/{id}")
    public UserAccount update(@PathVariable Long id, @Valid @RequestBody UserAccount user) {
        UserAccount existing = service.findById(id);
        existing.setUsername(user.getUsername());
        existing.setFullName(user.getFullName());
        existing.setRole(user.getRole());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
