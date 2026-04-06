import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = '/api';

  constructor(private http: HttpClient) {}

  // === Generic CRUD ===

  getAll<T>(path: string): Observable<T[]> {
    return this.http.get<T[]>(`${this.base}/${path}`);
  }

  getById<T>(path: string, id: number): Observable<T> {
    return this.http.get<T>(`${this.base}/${path}/${id}`);
  }

  create<T>(path: string, body: T): Observable<T> {
    return this.http.post<T>(`${this.base}/${path}`, body);
  }

  update<T>(path: string, id: number, body: T): Observable<T> {
    return this.http.put<T>(`${this.base}/${path}/${id}`, body);
  }

  delete(path: string, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${path}/${id}`);
  }

  // === Facilities ===

  getFacilities(): Observable<any[]> {
    return this.getAll('facilities');
  }

  // === Distributors ===

  getDistributors(): Observable<any[]> {
    return this.getAll('distributors');
  }

  // === Equipment ===

  getEquipment(): Observable<any[]> {
    return this.getAll('equipment');
  }

  getMatchingEquipment(lotId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/equipment/match/${lotId}`);
  }

  // === Users ===

  getUsers(): Observable<any[]> {
    return this.getAll('users');
  }

  // === Tenders ===

  getTenders(): Observable<any[]> {
    return this.getAll('tenders');
  }

  getTenderLots(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/tenders/${tenderId}/lots`);
  }

  getTenderApplies(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/tenders/${tenderId}/applies`);
  }

  // === Applies ===

  getApplies(): Observable<any[]> {
    return this.getAll('applies');
  }

  getApplyItems(applyId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/applies/${applyId}/items`);
  }
}
