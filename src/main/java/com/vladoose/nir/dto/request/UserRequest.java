package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRequest {

    @NotBlank(message = "Логин обязателен")
    @Size(min = 3, max = 100, message = "Логин должен быть от 3 до 100 символов")
    private String username;

    private String fullName;

    @NotBlank(message = "Роль обязательна")
    private String role;

    /**
     * Plain-text password. Required on create, optional on update
     * (null/blank means keep current password). Will be hashed in the
     * controller via {@link org.springframework.security.crypto.password.PasswordEncoder}.
     */
    private String password;
}
