import { Component } from '@angular/core';
import { MarketService, APP_NAME } from '../../services/market.service';

@Component({
  selector: 'app-about',
  standalone: true,
  template: `
    <h2>О системе</h2>
    <p class="subtitle">{{ appName }} — тендеры и заявки на медоборудование</p>

    <div class="about-section">
      <h3>Назначение</h3>
      <p>{{ appName }} автоматизирует участие в закупках медицинского оборудования: государственные
         тендеры (рынок РФ) и частные заявки клиник с проверкой регистрации в реестре (рынок KZ) —
         учёт, подбор оборудования, запросы коммерческих предложений дистрибьюторам.
         Активный рынок: <strong>{{ market.companyLabel() }} ({{ market.value === 'RF' ? 'РФ' : 'KZ' }})</strong>.</p>
    </div>

    <div class="about-section">
      <h3>Основные функции</h3>
      <div class="features-grid">
        <div class="feature"><span class="feature-title">Учёт тендеров</span><span class="feature-desc">Импорт и ведение реестра тендеров (Госзакуп РК, СК-Фармация) с отслеживанием статусов, сроков и требований</span></div>
        <div class="feature"><span class="feature-title">Подбор оборудования</span><span class="feature-desc">Подбор по габаритам/весу и разбор техспецификации; проверка регистрации в реестре НЦЭЛС РК</span></div>
        <div class="feature"><span class="feature-title">Запросы КП</span><span class="feature-desc">Формирование и отправка запросов коммерческих предложений дистрибьюторам, распознавание ответов</span></div>
        <div class="feature"><span class="feature-title">Аналитика</span><span class="feature-desc">Отчёты по тендерной деятельности, статистика по оборудованию и дистрибьюторам</span></div>
        <div class="feature"><span class="feature-title">Частные заявки</span><span class="feature-desc">Приём заявок клиник из Excel/почты, подбор поставщиков по брендам, сравнение предложений</span></div>
        <div class="feature"><span class="feature-title">Встроенная почта</span><span class="feature-desc">Отправка и приём писем без переключения в почтовый клиент</span></div>
      </div>
    </div>

    <div class="about-section">
      <h3>Технологический стек</h3>
      <div class="tech-grid">
        <div class="tech-item"><span class="tech-label">Сервер</span><span>Java 17, Spring Boot, Spring Data JPA</span></div>
        <div class="tech-item"><span class="tech-label">База данных</span><span>PostgreSQL</span></div>
        <div class="tech-item"><span class="tech-label">Клиент</span><span>Angular, TypeScript</span></div>
        <div class="tech-item"><span class="tech-label">Отчёты</span><span>JasperReports</span></div>
        <div class="tech-item"><span class="tech-label">Сборка</span><span>Gradle</span></div>
      </div>
    </div>

    <div class="about-section">
      <h3>Информация</h3>
      <div class="tech-grid">
        <div class="tech-item"><span class="tech-label">Версия</span><span>1.0.0</span></div>
        <div class="tech-item"><span class="tech-label">Организация</span><span>{{ market.value === 'RF' ? 'ООО «Регион-Мед»' : 'ТОО «West-Med»' }}</span></div>
        <div class="tech-item"><span class="tech-label">Год</span><span>2026</span></div>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 24px; }
    .about-section { margin-bottom: 24px; }
    .about-section h3 { font-size: 16px; color: #111827; margin: 0 0 12px; }
    .about-section p { font-size: 14px; color: #374151; line-height: 1.6; margin: 0; }
    .features-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .feature { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px; display: flex; flex-direction: column; }
    .feature-title { font-size: 14px; font-weight: 600; color: #111827; margin-bottom: 4px; }
    .feature-desc { font-size: 13px; color: #6b7280; line-height: 1.4; }
    .tech-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; }
    .tech-item { display: flex; flex-direction: column; padding: 8px 0; border-bottom: 1px solid #f3f4f6; }
    .tech-label { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin-bottom: 2px; }
    .tech-item span:not(.tech-label) { font-size: 14px; color: #111827; }
    @media (max-width: 900px) { .features-grid, .tech-grid { grid-template-columns: 1fr; } }
  `]
})
export class AboutComponent {
  readonly appName = APP_NAME;
  constructor(public market: MarketService) {}
}
