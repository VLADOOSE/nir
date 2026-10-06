package com.vladoose.nir;

import com.vladoose.nir.config.TlsDefaults;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Nir2Application {

    static {
        // До первого HTTPS-соединения: JDK читает этот флаг один раз (TlsDefaults — почему).
        TlsDefaults.enableAiaFetching();
    }

    public static void main(String[] args) {
        SpringApplication.run(Nir2Application.class, args);
    }

}

