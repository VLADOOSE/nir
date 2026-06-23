package com.vladoose.nir.config;

import com.vladoose.nir.service.RegistryImportService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100) // после базового DataInitializer
public class RegistryDataInitializer implements CommandLineRunner {

    private final RegistryImportService importService;

    public RegistryDataInitializer(RegistryImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(String... args) {
        int imported = importService.importIfEmpty();
        if (imported > 0) {
            System.out.println("Реестр МИ РК импортирован: " + imported + " записей");
        }
    }
}
