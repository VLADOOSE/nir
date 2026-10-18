import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { forkJoin, map, Observable, of, catchError } from 'rxjs';
import { LEAD_STATUS_LABELS } from '../shared/lead-labels';

export interface SearchResult {
  type: string;
  typeLabel: string;
  id: number;
  title: string;
  subtitle: string;
  /** Куда вести: команды роутера и параметры адреса (карточка — ?openId= или /:id). */
  commands: any[];
  queryParams?: Record<string, any>;
}

/** Не больше стольких результатов в группе «Обращения», «Частные заявки», «КП клиенту». */
const DEAL_GROUP_LIMIT = 5;

/** Подписи статусов КП — здесь, а не из shared/client-offer: тот модуль держится вне начального бандла (§12). */
const OFFER_STATUS: Record<string, string> = { DRAFT: 'черновик', SENT: 'отправлено', ACCEPTED: 'принято', REJECTED: 'отклонено' };

@Injectable({ providedIn: 'root' })
export class SearchService {
  constructor(private api: ApiService) {}

  /**
   * Тендеры, оборудование, учреждения, дистрибьюторы — фильтр списков здесь же; обращения, частные заявки и КП
   * клиенту (2026-10-16) — поиск сервера (`q`, все статусы): номер ЧЗ-…, клиент, телефон, позиции — как умеет каждый.
   */
  search(query: string): Observable<SearchResult[]> {
    const term = (query || '').trim();
    if (term.length < 2) return of([]);   // и запрос из одних пробелов — не запрос
    const q = term.toLowerCase();
    // limit — сервер отдаёт не больше пяти (без него — до 300 полных строк ради пяти показанных)
    const deal = { status: 'ALL', q: term, limit: DEAL_GROUP_LIMIT };
    return forkJoin({
      tenders: this.api.getTenders().pipe(catchError(() => of([]))),
      leads: this.api.getLeads(deal).pipe(catchError(() => of([]))),
      requests: this.api.getPrivateRequests(deal).pipe(catchError(() => of([]))),
      offers: this.api.getClientOffers(deal).pipe(catchError(() => of([]))),
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
        commands: ['/tenders'], queryParams: { openId: t.id }
      }));
      data.leads.slice(0, DEAL_GROUP_LIMIT).forEach((l: any) => {
        const who = l.contactName || l.company || l.contactPhone;
        results.push({
          type: 'lead', typeLabel: 'Обращение', id: l.id,
          title: who || l.subject,
          subtitle: [who ? l.subject : '', LEAD_STATUS_LABELS[l.status] || l.status, l.privateRequestNumber]
            .filter(Boolean).join(' · '),
          commands: ['/leads'], queryParams: { openId: l.id }
        });
      });
      data.requests.slice(0, DEAL_GROUP_LIMIT).forEach((r: any) => results.push({
        type: 'request', typeLabel: 'Частная заявка', id: r.id,
        title: r.number,
        subtitle: [r.clientLabel, r.stageText].filter(Boolean).join(' · '),
        commands: ['/private-requests', r.id]
      }));
      data.offers.slice(0, DEAL_GROUP_LIMIT).forEach((o: any) => results.push({
        type: 'offer', typeLabel: 'КП клиенту', id: o.id,
        title: `КП № ${o.number}`,
        subtitle: [o.clientName || 'Клиент не указан', OFFER_STATUS[o.status] || o.status,
                   o.tenderNumber ? 'из заявки ' + o.tenderNumber : ''].filter(Boolean).join(' · '),
        commands: ['/client-offers', o.id]
      }));
      data.equipment.filter((e: any) =>
        e.name?.toLowerCase().includes(q) ||
        e.manufact?.toLowerCase().includes(q) ||
        e.equipType?.toLowerCase().includes(q)
      ).slice(0, 5).forEach((e: any) => results.push({
        type: 'equipment', typeLabel: 'Оборудование', id: e.id,
        title: e.name,
        subtitle: `${e.manufact} — ${e.equipType || ''}`,
        commands: ['/equipment']
      }));
      data.facilities.filter((f: any) =>
        f.name?.toLowerCase().includes(q) ||
        f.inn?.includes(q)
      ).slice(0, 3).forEach((f: any) => results.push({
        type: 'facility', typeLabel: 'Учреждение', id: f.id,
        title: f.name,
        subtitle: f.inn || '',
        commands: ['/facilities']
      }));
      data.distributors.filter((d: any) =>
        d.name?.toLowerCase().includes(q) ||
        d.inn?.includes(q)
      ).slice(0, 3).forEach((d: any) => results.push({
        type: 'distributor', typeLabel: 'Дистрибьютор', id: d.id,
        title: d.name,
        subtitle: d.inn || '',
        commands: ['/distributors']
      }));
      return results;
    }));
  }
}
