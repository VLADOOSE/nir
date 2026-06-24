package com.vladoose.nir.config;

import com.vladoose.nir.context.MarketContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;

/**
 * Включает Hibernate-фильтр {@code marketFilter} на сессии, ПРИВЯЗАННОЙ к текущему
 * потоку (OSIV-сессия запроса или транзакционная сессия), непосредственно перед
 * вызовом любого репозитория.
 *
 * Почему aspect, а не HandlerInterceptor: на момент {@code preHandle} интерцептора
 * EntityManager ещё не привязан к потоку (OSIV биндит его позже), поэтому
 * {@code entityManager.unwrap(Session.class)} создаёт временную одноразовую сессию,
 * на которой фильтр включается и тут же выбрасывается — реальный запрос идёт по другой,
 * привязанной сессии без фильтра. Aspect выполняется в момент вызова репозитория, когда
 * привязанная сессия уже существует, поэтому фильтр гарантированно попадает на ту же
 * сессию, что и сам запрос.
 */
@Aspect
@Component
public class MarketFilterAspect {

    private final EntityManagerFactory entityManagerFactory;

    public MarketFilterAspect(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Before("execution(* org.springframework.data.repository.Repository+.*(..))")
    public void enableMarketFilter() {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (em == null) {
            return; // нет привязанной сессии (вне OSIV/транзакции) — нечего фильтровать
        }
        Session session = em.unwrap(Session.class);
        String market = MarketContext.get().name();
        Filter filter = session.getEnabledFilter("marketFilter");
        if (filter == null) {
            session.enableFilter("marketFilter").setParameter("market", market);
        } else {
            filter.setParameter("market", market);
        }
    }
}
