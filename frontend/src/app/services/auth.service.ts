import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, of, tap, catchError, map } from 'rxjs';

export interface AuthUser {
  id: number;
  username: string;
  fullName: string;
  role: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private base = '/api/auth';
  private currentUser$ = new BehaviorSubject<AuthUser | null>(null);

  user$ = this.currentUser$.asObservable();

  constructor(private http: HttpClient) {}

  loadCurrentUser(): Observable<AuthUser | null> {
    return this.http.get<AuthUser>(`${this.base}/me`, { withCredentials: true }).pipe(
      tap(user => this.currentUser$.next(user)),
      catchError(() => {
        this.currentUser$.next(null);
        return of(null);
      })
    );
  }

  login(username: string, password: string): Observable<AuthUser> {
    return this.http.post<AuthUser>(
      `${this.base}/login`,
      { username, password },
      { withCredentials: true }
    ).pipe(
      tap(user => this.currentUser$.next(user))
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.base}/logout`, {}, { withCredentials: true }).pipe(
      tap(() => this.currentUser$.next(null)),
      catchError(() => {
        this.currentUser$.next(null);
        return of(undefined as any);
      }),
      map(() => undefined)
    );
  }

  isLoggedIn(): boolean {
    return this.currentUser$.getValue() !== null;
  }

  getUser(): AuthUser | null {
    return this.currentUser$.getValue();
  }

  isAdmin(): boolean {
    return this.currentUser$.getValue()?.role === 'ROLE_ADMIN';
  }
}
