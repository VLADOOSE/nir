package com.vladoose.nir.config;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Читает X-Market и кладёт активный рынок в MarketContext (ThreadLocal) на время запроса.
 * Включение Hibernate-фильтра marketFilter вынесено в {@link MarketFilterAspect}, который
 * срабатывает в момент вызова репозитория — когда EntityManager уже привязан к потоку (OSIV),
 * и фильтр гарантированно попадает на ту же сессию, что и сам запрос.
 */
@Component
public class MarketInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // SECURITY NOTE: X-Market — это клиентски-утверждаемый заголовок UI-переключения рынка,
        // НЕ граница авторизации tenant-а. Если потребуется изоляция по классам пользователей,
        // рынок нужно выводить из допустимых рынков пользователя на сервере, а не из заголовка.
        MarketContext.set(Market.fromHeader(request.getHeader("X-Market")));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        MarketContext.clear();
    }
}
