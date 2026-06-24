package com.vladoose.nir.config;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Читает X-Market, кладёт активный рынок в MarketContext и включает Hibernate-фильтр
 * marketFilter на привязанной OSIV-сессии (preHandle выполняется ПОСЛЕ привязки EntityManager).
 */
@Component
public class MarketInterceptor implements HandlerInterceptor {

    private final EntityManager entityManager;

    public MarketInterceptor(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Market market = Market.fromHeader(request.getHeader("X-Market"));
        MarketContext.set(market);
        entityManager.unwrap(Session.class)
                .enableFilter("marketFilter").setParameter("market", market.name());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        MarketContext.clear();
    }
}
