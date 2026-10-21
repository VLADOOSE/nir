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
        applyTelegram(request, entity);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        UserAccount existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            existing.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        applyTelegram(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    /** Telegram ID из формы: не прислан — не трогаем, пусто — снять, иначе цифры и не у другого пользователя. */
    private void applyTelegram(UserRequest request, UserAccount entity) {
        String raw = request.getTelegramUserId();
        if (raw == null) return;
        String s = raw.strip();
        if (s.isEmpty()) {
            entity.setTelegramUserId(null);
            return;
        }
        if (!s.matches("\\d{1,15}")) throw new BadRequestException("Telegram ID — только цифры (бот пишет его по первому нажатию)");
        long id = Long.parseLong(s);
        service.findByTelegramUserId(id).filter(u -> !u.getId().equals(entity.getId())).ifPresent(u -> {
            throw new BadRequestException("Этот Telegram ID уже у пользователя «" + u.getUsername() + "»");
        });
        entity.setTelegramUserId(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
