package com.vladoose.nir.controller;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:5173")
public class UsersController {
    private final UserService svc;
    public UsersController(UserService svc){ this.svc = svc; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserAccount> listUsers(){ return svc.list(); }
}
