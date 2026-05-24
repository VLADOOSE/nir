import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { forkJoin, map, Observable, of, catchError } from 'rxjs';

export interface SearchResult {
  type: string;
  typeLabel: string;
  id: number;
  title: string;
  subtitle: string;
  route: string;
}

@Injectable({ providedIn: 'root' })
export class SearchService {
  constructor(private api: ApiService) {}

  search(query: string): Observable<SearchResult[]> {
    if (!query || query.length < 2) return of([]);
    const q = query.toLowerCase();
    return forkJoin({
      tenders: this.api.getTenders().pipe(catchError(() => of([]))),
      equipment: this.api.getEquipment().pipe(catchError(() => of([]))),
      facilities: this.api.getFacilities().pipe(catchError(() => of([]))),
      distributors: this.api.getDistributors().pipe(catchError(() => of([])))
    }).pipe(map(data => {
      const results: SearchResult[] = [];
      data.tenders.filter((t: any) =>
        t.tenderNumber?.toLowerCase().includes(q) ||
        t.description?.toLowerCase().includes(q) ||
        t.facility?.name?.toLowerCase().includes(q)
      ).slice(0, 5).forEach((t: any) => results.push({
        type: 'tender', typeLabel: 'Тендер', id: t.id,
        title: `№ ${t.tenderNumber}`,
        subtitle: t.description?.substring(0, 60) || t.facility?.name || '',
        route: '/tenders'
      }));
      data.equipment.filter((e: any) =>
        e.name?.toLowerCase().includes(q) ||
        e.manufact?.toLowerCase().includes(q) ||
        e.equipType?.toLowerCase().includes(q)
      ).slice(0, 5).forEach((e: any) => results.push({
        type: 'equipment', typeLabel: 'Оборудование', id: e.id,
        title: e.name,
        subtitle: `${e.manufact} — ${e.equipType || ''}`,
        route: '/equipment'
      }));
      data.facilities.filter((f: any) =>
        f.name?.toLowerCase().includes(q) ||
        f.inn?.includes(q)
      ).slice(0, 3).forEach((f: any) => results.push({
        type: 'facility', typeLabel: 'Учреждение', id: f.id,
        title: f.name,
        subtitle: f.inn || '',
        route: '/facilities'
      }));
      data.distributors.filter((d: any) =>
        d.name?.toLowerCase().includes(q) ||
        d.inn?.includes(q)
      ).slice(0, 3).forEach((d: any) => results.push({
        type: 'distributor', typeLabel: 'Дистрибьютор', id: d.id,
        title: d.name,
        subtitle: d.inn || '',
        route: '/distributors'
      }));
      return results;
    }));
  }
}
