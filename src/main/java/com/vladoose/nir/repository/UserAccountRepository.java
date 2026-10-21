package com.vladoose.nir.repository;

import com.vladoose.nir.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByTelegramUserId(Long telegramUserId);

    boolean existsByUsername(String username);
}
