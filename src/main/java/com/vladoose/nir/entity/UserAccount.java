package com.vladoose.nir.entity;

import jakarta.persistence.*;
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

    @Column(length = 100, unique = true, nullable = false)
    private String username;

    @Column(name = "full_name")
    private String fullName;

    @Column(length = 50, nullable = false)
    private String role;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    /** Telegram ID — для кнопок бота (спека 2026-10-21-tender-telegram-bot §8, V41); пусто — бот учётку не знает. */
    @Column(name = "telegram_user_id", unique = true)
    private Long telegramUserId;
}
