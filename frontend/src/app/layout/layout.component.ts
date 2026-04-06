import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <header class="header">
        <span class="header-title">АИС Регион-Мед</span>
      </header>
      <div class="body">
        <nav class="sidebar">
          <a routerLink="/tenders" routerLinkActive="active">Тендеры</a>
          <a routerLink="/equipment" routerLinkActive="active">Оборудование</a>
          <a routerLink="/facilities" routerLinkActive="active">Учреждения</a>
          <a routerLink="/distributors" routerLinkActive="active">Дистрибьюторы</a>
          <a routerLink="/applies" routerLinkActive="active">Заявки</a>
          <a routerLink="/users" routerLinkActive="active">Пользователи</a>
        </nav>
        <main class="content">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
  styles: [`
    .layout {
      display: flex;
      flex-direction: column;
      height: 100vh;
    }
    .header {
      background: #1a56db;
      color: #fff;
      padding: 12px 24px;
      font-size: 18px;
      flex-shrink: 0;
    }
    .header-title {
      font-weight: 600;
    }
    .body {
      display: flex;
      flex: 1;
      overflow: hidden;
    }
    .sidebar {
      width: 220px;
      background: #f3f4f6;
      border-right: 1px solid #e5e7eb;
      display: flex;
      flex-direction: column;
      padding: 16px 0;
      flex-shrink: 0;
    }
    .sidebar a {
      padding: 10px 24px;
      color: #374151;
      text-decoration: none;
      font-size: 14px;
      transition: background 0.15s;
    }
    .sidebar a:hover {
      background: #e5e7eb;
    }
    .sidebar a.active {
      background: #dbeafe;
      color: #1a56db;
      font-weight: 600;
      border-right: 3px solid #1a56db;
    }
    .content {
      flex: 1;
      padding: 24px;
      overflow-y: auto;
      background: #fff;
    }
  `]
})
export class LayoutComponent {}
