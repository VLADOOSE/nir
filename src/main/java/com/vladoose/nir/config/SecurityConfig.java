package com.vladoose.nir.config;

import com.vladoose.nir.integration.waha.WahaWebhookController;
import com.vladoose.nir.security.SessionIdRotationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Value("${passkeys.rp-id}") String passkeyRpId,
                                           @Value("${passkeys.allowed-origins}") String[] passkeyOrigins) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // вход по ключу (passkeys): встроенное удаление ключа в Spring 6.5.5 не проверяет ни владельца,
                        // ни даже вход, а его фильтр стоит после этой проверки прав — закрыто для всех; свои ключи
                        // удаляются через /api/passkeys/{id} (спека passkeys-login §5.2)
                        .requestMatchers(HttpMethod.DELETE, "/webauthn/**").denyAll()
                        .requestMatchers("/webauthn/register", "/webauthn/register/**").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        // калитка ais.westmed.kz: её зовут nginx (auth_request) и недопущенное устройство — до входа в АИС
                        .requestMatchers("/api/gate/**").permitAll()
                        // вебхук WAHA: зовёт только сама WAHA внутри сети docker; защита — подпись HMAC (спека whatsapp-waha §10)
                        .requestMatchers(HttpMethod.POST, WahaWebhookController.PATH).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                // встроенные эндпоинты passkeys: POST /webauthn/register/options, /webauthn/register,
                // /webauthn/authenticate/options, /login/webauthn; ключ навсегда привязан к домену rpId
                .webAuthn(w -> w
                        .rpName("АИС Медзакупки")
                        .rpId(passkeyRpId)
                        .allowedOrigins(passkeyOrigins)
                        .disableDefaultRegistrationPage(true))
                // смена id сессии перед входом по ключу; место фильтра формы входа (она выключена) — раньше
                // фильтра Spring для /login/webauthn, который стоит перед BasicAuthenticationFilter
                .addFilterBefore(new SessionIdRotationFilter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
