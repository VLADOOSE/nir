import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private currentUser: any = null;

  private users = [
    { username: 'admin', password: 'admin', fullName: 'Администратор', role: 'ROLE_ADMIN' },
    { username: 'operator', password: 'operator', fullName: 'Оператор Иванов', role: 'ROLE_USER' }
  ];

  login(username: string, password: string): boolean {
    const user = this.users.find(u => u.username === username && u.password === password);
    if (user) {
      this.currentUser = user;
      sessionStorage.setItem('currentUser', JSON.stringify(user));
      return true;
    }
    return false;
  }

  logout() {
    this.currentUser = null;
    sessionStorage.removeItem('currentUser');
  }

  isLoggedIn(): boolean {
    if (this.currentUser) return true;
    const saved = sessionStorage.getItem('currentUser');
    if (saved) {
      this.currentUser = JSON.parse(saved);
      return true;
    }
    return false;
  }

  getUser(): any {
    if (!this.currentUser) {
      const saved = sessionStorage.getItem('currentUser');
      if (saved) this.currentUser = JSON.parse(saved);
    }
    return this.currentUser;
  }

  isAdmin(): boolean {
    return this.getUser()?.role === 'ROLE_ADMIN';
  }
}
