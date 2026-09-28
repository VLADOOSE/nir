package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.PasskeyConfigResponse;
import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository userRepository;
    private final String passkeyRpId;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserAccountRepository userRepository,
                          @Value("${passkeys.rp-id}") String passkeyRpId) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passkeyRpId = passkeyRpId;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(body.username(), body.password());
            Authentication auth = authenticationManager.authenticate(token);

            // сессия, открытая до входа, могла быть подсунута — после входа её id ничего не стоит (спека passkeys-login §5.3)
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);

            return ResponseEntity.ok(buildUserInfo(body.username()));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(401).body(Map.of("message", "Неверный логин или пароль"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(401).body(Map.of("message", "Не авторизован"));
        }
        return ResponseEntity.ok(buildUserInfo(auth.getName()));
    }

    /** Домен ключей входа: кнопка «Войти с Face ID» показывается только на нём (спека passkeys-login §6). */
    @GetMapping("/passkey-config")
    public PasskeyConfigResponse passkeyConfig() {
        return new PasskeyConfigResponse(passkeyRpId);
    }

    private Map<String, Object> buildUserInfo(String username) {
        UserAccount user = userRepository.findByUsername(username).orElseThrow();
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "fullName", user.getFullName() == null ? "" : user.getFullName(),
                "role", user.getRole()
        );
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
