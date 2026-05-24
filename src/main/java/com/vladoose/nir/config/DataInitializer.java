package com.vladoose.nir.config;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.UserAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserAccountRepository userRepository;
    private final EquipmentTypeRepository equipmentTypeRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserAccountRepository userRepository,
                           EquipmentTypeRepository equipmentTypeRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.equipmentTypeRepository = equipmentTypeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (equipmentTypeRepository.count() == 0) {
            for (String name : List.of("УЗИ", "Рентген", "ИВЛ", "Монитор")) {
                equipmentTypeRepository.save(EquipmentType.builder().name(name).build());
            }
        }
        if (userRepository.count() == 0) {
            userRepository.save(UserAccount.builder()
                    .username("admin")
                    .fullName("Администратор")
                    .role("ROLE_ADMIN")
                    .passwordHash(passwordEncoder.encode("admin"))
                    .build());
            userRepository.save(UserAccount.builder()
                    .username("operator")
                    .fullName("Оператор Иванов")
                    .role("ROLE_USER")
                    .passwordHash(passwordEncoder.encode("operator"))
                    .build());
        }
    }
}
