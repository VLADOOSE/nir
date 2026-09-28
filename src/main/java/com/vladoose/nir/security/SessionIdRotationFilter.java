package com.vladoose.nir.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Меняет id сессии перед входом по ключу (POST /login/webauthn): встроенный фильтр Spring 6.5.5 его не меняет.
 * Сессия, открытая до входа, могла быть подсунута — например, cookie JSESSIONID на весь домен с другого сайта
 * *.westmed.kz; после входа такой id ничего не стоит. Содержимое сессии (в том числе параметры входа по ключу)
 * сохраняется, cookie выдаётся заново. Спека passkeys-login §5.3.
 * Не бин: @Component зарегистрировал бы его ещё и общим сервлет-фильтром. Создаётся в SecurityConfig.
 */
public class SessionIdRotationFilter extends OncePerRequestFilter {

    // тот же матчер, что у самого фильтра входа по ключу в Spring
    private static final RequestMatcher KEY_LOGIN =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login/webauthn");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !KEY_LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        chain.doFilter(request, response);
    }
}
