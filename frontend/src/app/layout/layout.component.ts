import { Component, HostListener, ChangeDetectorRef } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { NgFor, NgIf, AsyncPipe } from '@angular/common';
import { SearchService, SearchResult } from '../services/search.service';
import { AuthService } from '../services/auth.service';
import { NotificationComponent } from '../components/notification/notification.component';
import { ConfirmComponent } from '../components/confirm/confirm.component';

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule, NgFor, NgIf, AsyncPipe, NotificationComponent, ConfirmComponent],
  template: `
    <app-notifications></app-notifications>
    <app-confirm></app-confirm>
    <div class="layout">
      <header class="header">
        <div class="header-left">
          <span class="logo">РМ</span>
          <span class="header-title">АИС Регион-Мед</span>
        </div>
        <div class="header-search">
          <input type="text" placeholder="Поиск по тендерам, оборудованию, учреждениям..."
                 [(ngModel)]="searchQuery" (input)="onSearch()" (focus)="showResults = searchResults.length > 0" />
          <div class="search-results" *ngIf="showResults && searchResults.length > 0">
            <div class="search-result" *ngFor="let r of searchResults" (click)="onSelectResult(r)">
              <span class="result-type" [class]="'type-' + r.type">{{ r.typeLabel }}</span>
              <div class="result-content">
                <div class="result-title">{{ r.title }}</div>
                <div class="result-subtitle">{{ r.subtitle }}</div>
              </div>
            </div>
          </div>
        </div>
        <div class="header-right" *ngIf="auth.user$ | async as user">
          <span class="user-name">{{ user.fullName || user.username }}</span>
          <span class="role-badge" [class.role-admin]="user.role === 'ROLE_ADMIN'">
            {{ user.role === 'ROLE_ADMIN' ? 'Админ' : 'Оператор' }}
          </span>
          <button class="btn-logout" (click)="onLogout()">Выйти</button>
        </div>
      </header>
      <div class="body">
        <nav class="sidebar">
          <div class="nav-group">
            <a routerLink="/dashboard" routerLinkActive="active" [routerLinkActiveOptions]="{exact: true}">🏠 Главная</a>
          </div>
          <div class="nav-group">
            <span class="nav-group-title">Тендеры</span>
            <a routerLink="/tenders" routerLinkActive="active" [routerLinkActiveOptions]="{exact: true}">📋 Все тендеры</a>
            <a routerLink="/tenders/search" routerLinkActive="active">🔍 Поиск тендеров</a>
          </div>
          <div class="nav-group">
            <span class="nav-group-title">Каталог</span>
            <a routerLink="/equipment" routerLinkActive="active">🏥 Оборудование</a>
            <a *ngIf="auth.isAdmin()" routerLink="/equipment-types" routerLinkActive="active">🏷️ Типы оборудования</a>
            <a routerLink="/facilities" routerLinkActive="active">🏢 Учреждения</a>
            <a routerLink="/distributors" routerLinkActive="active">🚚 Дистрибьюторы</a>
          </div>
          <div class="nav-group">
            <span class="nav-group-title">Заявки</span>
            <a routerLink="/applies" routerLinkActive="active">📝 Заявки на участие</a>
          </div>
          <div class="nav-group">
            <span class="nav-group-title">Система</span>
            <a routerLink="/reports" routerLinkActive="active">📊 Отчёты</a>
            <a *ngIf="auth.isAdmin()" routerLink="/users" routerLinkActive="active">👥 Пользователи</a>
            <a routerLink="/about" routerLinkActive="active">ℹ️ О системе</a>
          </div>
        </nav>
        <main class="content">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: flex; flex-direction: column; height: 100vh; }
    .header {
      background: #1a56db; color: #fff; padding: 0 24px; height: 52px;
      display: flex; align-items: center; justify-content: space-between; flex-shrink: 0;
    }
    .header-left { display: flex; align-items: center; gap: 12px; }
    .logo {
      width: 32px; height: 32px; border-radius: 50%; background: rgba(255,255,255,0.2);
      display: flex; align-items: center; justify-content: center;
      font-weight: 700; font-size: 13px; letter-spacing: 1px;
    }
    .header-title { font-weight: 600; font-size: 16px; }
    .header-right { display: flex; align-items: center; gap: 12px; font-size: 14px; opacity: 0.95; }
    .user-name { font-weight: 500; }
    .role-badge { background: rgba(255,255,255,0.2); padding: 2px 10px; border-radius: 10px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
    .role-badge.role-admin { background: #fef3c7; color: #92400e; }
    .btn-logout { background: rgba(255,255,255,0.15); color: #fff; border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500; }
    .btn-logout:hover { background: rgba(255,255,255,0.3); }

    .header-search { flex: 1; max-width: 500px; margin: 0 24px; position: relative; }
    .header-search input { width: 100%; padding: 8px 16px; border: none; border-radius: 6px; font-size: 14px; background: rgba(255,255,255,0.15); color: #fff; outline: none; box-sizing: border-box; }
    .header-search input::placeholder { color: rgba(255,255,255,0.6); }
    .header-search input:focus { background: rgba(255,255,255,0.25); }
    .search-results { position: absolute; top: 100%; left: 0; right: 0; background: #fff; border-radius: 0 0 8px 8px; box-shadow: 0 8px 24px rgba(0,0,0,0.15); max-height: 400px; overflow-y: auto; z-index: 100; }
    .search-result { display: flex; align-items: center; gap: 12px; padding: 10px 16px; cursor: pointer; border-bottom: 1px solid #f3f4f6; }
    .search-result:hover { background: #f9fafb; }
    .result-type { font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: 600; white-space: nowrap; }
    .type-tender { background: #dbeafe; color: #1a56db; }
    .type-equipment { background: #d1fae5; color: #065f46; }
    .type-facility { background: #fef3c7; color: #92400e; }
    .type-distributor { background: #ede9fe; color: #5b21b6; }
    .result-content { flex: 1; min-width: 0; }
    .result-title { font-size: 14px; font-weight: 500; color: #111827; }
    .result-subtitle { font-size: 12px; color: #6b7280; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

    .body { display: flex; flex: 1; overflow: hidden; }
    .sidebar {
      width: 240px; background: #f8f9fb; border-right: 1px solid #e5e7eb;
      display: flex; flex-direction: column; padding: 12px 0; flex-shrink: 0; overflow-y: auto;
    }
    .nav-group { margin-bottom: 8px; }
    .nav-group-title {
      display: block; padding: 8px 20px 4px; font-size: 11px; font-weight: 600;
      color: #9ca3af; text-transform: uppercase; letter-spacing: 0.5px;
    }
    .sidebar a {
      display: block; padding: 9px 20px; color: #374151; text-decoration: none;
      font-size: 14px; transition: all 0.15s; border-left: 3px solid transparent;
    }
    .sidebar a:hover { background: #e5e7eb; }
    .sidebar a.active {
      background: #1a56db; color: #fff; border-left-color: #fff; font-weight: 500;
    }
    .content { flex: 1; padding: 24px 32px; overflow-y: auto; background: #fff; }
  `]
})
export class LayoutComponent {
  searchQuery = '';
  searchResults: SearchResult[] = [];
  showResults = false;

  constructor(private searchService: SearchService, private router: Router, private cdr: ChangeDetectorRef, public auth: AuthService) {}

  onSearch() {
    this.searchService.search(this.searchQuery).subscribe(results => {
      this.searchResults = results;
      this.showResults = results.length > 0;
      this.cdr.detectChanges();
    });
  }

  onSelectResult(r: SearchResult) {
    this.showResults = false;
    this.searchQuery = '';
    if (r.type === 'tender') {
      this.router.navigate([r.route], { queryParams: { openId: r.id } });
    } else {
      this.router.navigate([r.route]);
    }
  }

  onLogout() {
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event) {
    const target = event.target as HTMLElement;
    if (!target.closest('.header-search')) {
      this.showResults = false;
    }
  }
}
