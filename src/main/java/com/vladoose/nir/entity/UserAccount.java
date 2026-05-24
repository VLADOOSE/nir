package com.vladoose.nir.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

@Entity
@Table(name = "user_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Логин обязателен")
    @Size(min = 3, max = 100, message = "Логин должен быть от 3 до 100 символов")
    @Column(length = 100, unique = true, nullable = false)
    private String username;

    @Column(name = "full_name")
    private String fullName;

    @NotBlank(message = "Роль обязательна")
    @Column(length = 50)
    private String role;
}
