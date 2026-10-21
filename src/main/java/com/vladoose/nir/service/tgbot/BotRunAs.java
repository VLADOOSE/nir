package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.util.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Действие кнопки бота от имени учётки АИС (спека 2026-10-21-tender-telegram-bot §8): рынок KZ, вход пользователя, пометка
 * «Telegram» и привязанная к потоку сессия JPA без транзакции — как OSIV у запросов с экрана: прежние сервисы (письмо,
 * бланк, ленивые связи, фильтр рынка) работают так же, а авторы пишутся «vlad, Telegram».
 */
@Component
public class BotRunAs {

    private final EntityManagerFactory emf;

    public BotRunAs(EntityManagerFactory emf) { this.emf = emf; }

    public void run(UserAccount user, Runnable work) {
        MarketContext.set(Market.KZ);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                List.of(new SimpleGrantedAuthority(user.getRole()))));
        CurrentUser.setVia("Telegram");
        boolean bound = TransactionSynchronizationManager.hasResource(emf);   // в тестах сессия уже есть
        EntityManager em = null;
        if (!bound) {
            em = emf.createEntityManager();
            TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        }
        try {
            work.run();
        } finally {
            if (!bound) {
                TransactionSynchronizationManager.unbindResource(emf);
                EntityManagerFactoryUtils.closeEntityManager(em);
            }
            CurrentUser.setVia(null);
            SecurityContextHolder.clearContext();
            MarketContext.clear();
        }
    }
}
