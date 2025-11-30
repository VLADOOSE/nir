package com.vladoose.nir.repository;

import com.vladoose.nir.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {}
