package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.UserRequest;
import com.vladoose.nir.dto.response.UserResponse;
import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.UserMapper;
import com.vladoose.nir.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService service;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService service, UserMapper mapper, PasswordEncoder passwordEncoder) {
        this.service = service;
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public UserResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody UserRequest request) {
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BadRequestException("Пароль обязателен при создании пользователя");
        }
        UserAccount entity = mapper.toEntity(request);
        entity.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        UserAccount existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            existing.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
