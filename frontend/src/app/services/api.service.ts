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

  getMatchingEquipment(lotId: number): Observable<any> {
    return this.http.get<any>(`${this.base}/equipment/match/${lotId}`);
  }

  postMatchEquipment(lotId: number, body: { preset: string; weights?: { price: number; margin: number; track: number; dim: number } }): Observable<any> {
    return this.http.post<any>(`${this.base}/equipment/match/${lotId}`, body);
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

  getAllApplyItems(): Observable<any[]> {
    return this.getAll('apply-items');
  }

  downloadApplyReport(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/applies/${id}/pdf`, { responseType: 'blob' });
  }

  // === Price Requests ===

  getPriceRequests(): Observable<any[]> {
    return this.getAll('price-requests');
  }

  getPriceRequestsByLot(lotId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/price-requests/by-lot/${lotId}`);
  }

  createPriceRequest(body: any): Observable<any> {
    return this.create('price-requests', body);
  }

  updatePriceRequest(id: number, body: any): Observable<any> {
    return this.update('price-requests', id, body);
  }

  deletePriceRequest(id: number): Observable<void> {
    return this.delete('price-requests', id);
  }

  // === Tender Search ===

  searchTenders(params: any): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/tenders/search`, { params });
  }

  // === Reports ===

  getTenderStats(): Observable<any> {
    return this.http.get<any>(`${this.base}/reports/tender-stats`);
  }

  getEquipmentDemand(): Observable<any> {
    return this.http.get<any>(`${this.base}/reports/equipment-demand`);
  }

  getDistributorStats(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/reports/distributor-stats`);
  }

  getDistributorPrStats(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/reports/distributor-pr-stats`);
  }

  getProfitabilityReport(): Observable<any> {
    return this.http.get<any>(`${this.base}/reports/profitability`);
  }

  downloadTenderReport(status?: string): Observable<Blob> {
    const params = status ? `?status=${status}` : '';
    return this.http.get(`${this.base}/reports/tender-pdf${params}`, { responseType: 'blob' });
  }

  downloadProfitabilityExcel(): Observable<Blob> {
    return this.http.get(`${this.base}/reports/profitability-excel`, { responseType: 'blob' });
  }

  // === Equipment Types ===

  getEquipmentTypes(): Observable<any[]> { return this.getAll('equipment-types'); }
  createEquipmentType(body: any): Observable<any> { return this.create('equipment-types', body); }
  updateEquipmentType(id: number, body: any): Observable<any> { return this.update('equipment-types', id, body); }
  deleteEquipmentType(id: number): Observable<void> { return this.delete('equipment-types', id); }

  // === Bulk Price ===

  bulkPricePreview(tenderId: number): Observable<any> {
    return this.http.get<any>(`${this.base}/bulk-price/preview/${tenderId}`);
  }

  bulkPriceSend(body: any): Observable<number> {
    return this.http.post<number>(`${this.base}/bulk-price/send`, body);
  }

  // === Equipment stats ===

  getEquipmentStats(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/equipment/${id}/stats`);
  }

  // === Auto-fill apply ===

  autoFillApply(applyId: number, markupPercent?: number): Observable<any> {
    const body = markupPercent != null ? { markupPercent } : {};
    return this.http.post<any>(`${this.base}/applies/${applyId}/auto-fill`, body);
  }

  // === Price Request responses ===

  updatePriceRequestResponses(id: number, updates: any[]): Observable<any> {
    return this.http.put<any>(`${this.base}/price-requests/${id}/responses`, updates);
  }

  getPriceRequestsByTender(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/price-requests/by-tender/${tenderId}`);
  }

  closePriceRequest(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/price-requests/${id}/close`, {});
  }

  acceptPriceRequest(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/price-requests/${id}/accept`, {});
  }

  // === Реестр / сверка ===
  getRegistryReconciliation(status?: string, candidates: number = 5): Observable<any[]> {
    let url = `${this.base}/registry/reconciliation?candidates=${candidates}`;
    if (status) { url += `&status=${encodeURIComponent(status)}`; }
    return this.http.get<any[]>(url);
  }

  getRegistryCandidatesForEquipment(equipmentId: number, limit: number = 5): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/registry/candidates/equipment/${equipmentId}?limit=${limit}`);
  }

  searchRegistry(q: string, limit: number = 20): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/registry/search?q=${encodeURIComponent(q)}&limit=${limit}`);
  }

  setEquipmentRegistration(equipmentId: number, action: string, regNumber?: string): Observable<any> {
    return this.http.post(`${this.base}/equipment/${equipmentId}/registration`, { action, regNumber });
  }

  refreshRegistry(): Observable<any> {
    return this.http.post(`${this.base}/registry/refresh`, {});
  }

  // === Частные заявки ===
  getPrivateRequests(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/private-requests`);
  }
  getPrivateRequest(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/private-requests/${id}`);
  }
  getPrivateRequestSourcing(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/private-requests/${id}/sourcing`);
  }
  createPrivateRequest(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/private-requests`, body);
  }

  previewImport(file: File): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/private-requests/import/preview`, fd);
  }

  commitImport(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/private-requests/import/commit`, body);
  }
}
