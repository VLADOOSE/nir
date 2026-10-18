import { Injectable } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { EquipmentHit, NewPriceLine, PreviewPages, RegistrySuggestion, SuggestionRow, SupplierOffer } from '../shared/client-offer';
import type { SupplierReply, SupplierReplyRateResult } from '../shared/supplier-reply';
import type {
  DocFormat, LineInput, LinesMode, ParsedLine, PrField, PrLine, PrLineField, PrListItem, PrView, Prices, RegAction,
  RegistryRecord, RfqPreviewBody, RfqSendBody, SendResult, SiteProduct, Sourcing,
} from '../shared/private-request';

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

  getTenderWorkStages(): Observable<{ [tenderId: number]: string }> {
    return this.http.get<{ [tenderId: number]: string }>(`${this.base}/tenders/work-stages`);
  }

  getTenderLots(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/tenders/${tenderId}/lots`);
  }

  getTenderApplies(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/tenders/${tenderId}/applies`);
  }

  importKzTenders(region?: string): Observable<any> {
    const params = region ? { params: { region } } : {};
    return this.http.post<any>(`${this.base}/tenders/import-kz`, {}, params);
  }

  getKzImportStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/tenders/import-kz/status`);
  }

  importSkTenders(): Observable<any> {
    return this.http.post<any>(`${this.base}/tenders/import-sk`, {});
  }

  getSkImportStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/tenders/import-sk/status`);
  }

  getLotRegistryCandidates(lotId: number, limit = 5): Observable<any> {
    return this.http.get<any>(`${this.base}/lots/${lotId}/registry-candidates`, { params: { limit } });
  }

  getRegistryDetail(regNumber: string): Observable<any> {
    return this.http.get<any>(`${this.base}/registry/detail`, { params: { regNumber } });
  }

  complectSearch(lotId: number, term?: string): Observable<any> {
    const params: any = term ? { term } : {};
    return this.http.post<any>(`${this.base}/lots/${lotId}/complect-search`, {}, { params });
  }

  adoptComponent(lotId: number, regNumber: string, partNumber: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/adopt-component`, { regNumber, partNumber });
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

  /** Единый канал отправки КП: письма поставщикам + записи PriceRequest. */
  sendPriceRequests(body: {
    tenderId: number;
    distributorIds: number[];
    items: { tenderLotId: number; medEquipmentId: number | null; requestedQuantity: number | null }[];
    subjectOverride?: string;
    bodyOverride?: string;
    replyBy?: string | null;
    withDocument?: boolean;
    withStamp?: boolean;
  }): Observable<any[]> {
    return this.http.post<any[]>(`${this.base}/price-requests/send`, body);
  }

  /** Черновой текст письма КП (тема без токена — он присвоится при отправке). */
  previewKp(body: {
    tenderId: number;
    distributorIds: number[];
    items: { tenderLotId: number; medEquipmentId: number | null; requestedQuantity: number | null }[];
    replyBy?: string | null;
    withDocument?: boolean;
    withStamp?: boolean;
  }): Observable<{ subject: string; body: string }> {
    return this.http.post<{ subject: string; body: string }>(`${this.base}/price-requests/preview`, body);
  }

  resendPriceRequest(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/price-requests/${id}/resend`, {});
  }

  /** Подсказки поставщиков для запроса КП по лотам тендера (опц. точечный термин Tier 2). */
  getLotSourcing(tenderId: number, lotIds: number[], term?: string): Observable<any> {
    const params: any = { lotIds: lotIds.join(',') };
    if (term) params.term = term;
    return this.http.get<any>(`${this.base}/tenders/${tenderId}/lot-sourcing`, { params });
  }

  /** Назначить/снять вид МИ лота. */
  setLotEquipmentType(lotId: number, typeId: number | null): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/equipment-type`, { typeId });
  }

  setProposedEquipment(lotId: number, equipmentId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/proposed-equipment`, { equipmentId });
  }

  clearProposedEquipment(lotId: number): Observable<any> {
    return this.http.delete<any>(`${this.base}/lots/${lotId}/proposed-equipment`);
  }

  /** Скачать и разобрать «Техническую спецификацию» импортного лота с goszakup. */
  parseLotTechSpec(lotId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/parse-techspec`, {});
  }

  /** «Взять из реестра в работу»: РУ → позиция каталога → предложенная модель лота. */
  adoptRegistryForLot(lotId: number, regNumber: string): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/adopt-registry`, { regNumber });
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

  /** Экран «Ответ поставщика» (спека 2026-10-15 §4.1); fileId — показать таблицу этого файла. */
  getSupplierReply(id: number, fileId?: number | null): Observable<SupplierReply> {
    const params: Record<string, string> = fileId != null ? { fileId: String(fileId) } : {};
    return this.http.get<SupplierReply>(`${this.base}/price-requests/${id}/reply`, { params });
  }
  uploadSupplierReplyFile(id: number, file: File): Observable<SupplierReply> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/files`, fd);
  }
  applySupplierReplyFile(id: number, fileId: number): Observable<SupplierReply> {
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}/apply`, {});
  }
  setSupplierReplyColumns(id: number, fileId: number, roles: { index: number; role: string }[]): Observable<SupplierReply> {
    return this.http.put<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}/columns`, roles);
  }
  deleteSupplierReplyFile(id: number, fileId: number): Observable<SupplierReply> {
    return this.http.delete<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}`);
  }
  setSupplierReplyRate(id: number, fileId: number, rate: number | null): Observable<SupplierReplyRateResult> {
    return this.http.put<SupplierReplyRateResult>(`${this.base}/price-requests/${id}/files/${fileId}/rate`, { rate });
  }
  /** Валюта цен файла, выбранная на экране сверки (KZT / RUB / USD / EUR / CNY): цены файла подставляются заново. */
  setSupplierReplyCurrency(id: number, fileId: number, currency: string): Observable<SupplierReplyRateResult> {
    return this.http.put<SupplierReplyRateResult>(`${this.base}/price-requests/${id}/files/${fileId}/currency`, { currency });
  }
  verifySupplierReply(id: number): Observable<SupplierReply> {
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/verify`, {});
  }
  downloadSupplierReplyFile(id: number, fileId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/price-requests/${id}/files/${fileId}`, { responseType: 'blob', observe: 'response' });
  }
  supplierReplyPage(id: number, fileId: number, page: number): Observable<Blob> {
    return this.http.get(`${this.base}/price-requests/${id}/files/${fileId}/pages/${page}`, { responseType: 'blob' });
  }

  updatePriceRequestResponses(id: number, updates: any[]): Observable<any> {
    return this.http.put<any>(`${this.base}/price-requests/${id}/responses`, updates);
  }

  getPriceRequestsByTender(tenderId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/price-requests/by-tender/${tenderId}`);
  }

  getOfferComparison(tenderId: number): Observable<any> {
    return this.http.get<any>(`${this.base}/tenders/${tenderId}/offer-comparison`);
  }

  assignWinner(tenderId: number, body: { lotId: number; priceRequestId: number; markupPercent?: number }): Observable<any> {
    return this.http.post<any>(`${this.base}/tenders/${tenderId}/assign-winner`, body);
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

  searchRegistry(q: string, limit: number = 20): Observable<RegistryRecord[]> {
    return this.http.get<RegistryRecord[]>(`${this.base}/registry/search?q=${encodeURIComponent(q)}&limit=${limit}`);
  }

  setEquipmentRegistration(equipmentId: number, action: string, regNumber?: string): Observable<any> {
    return this.http.post(`${this.base}/equipment/${equipmentId}/registration`, { action, regNumber });
  }

  refreshRegistry(): Observable<any> {
    return this.http.post(`${this.base}/registry/refresh`, {});
  }

  // === Частные заявки (страница-рабочее место, спека 2026-10-12) ===
  getPrivateRequests(params: { status?: string; q?: string } = {}): Observable<PrListItem[]> {
    const qs = new URLSearchParams();
    if (params.status) qs.set('status', params.status);
    if (params.q) qs.set('q', params.q);
    const s = qs.toString();
    return this.http.get<PrListItem[]>(`${this.base}/private-requests${s ? '?' + s : ''}`);
  }
  getPrivateRequest(id: number): Observable<PrView> {
    return this.http.get<PrView>(`${this.base}/private-requests/${id}`);
  }
  createPrivateRequest(): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests`, {});
  }
  updatePrivateRequestField(id: number, field: PrField, value: string | null): Observable<PrView> {
    return this.http.patch<PrView>(`${this.base}/private-requests/${id}`, { field, value });
  }
  savePrivateRequestClient(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/save-client`, {});
  }
  closePrivateRequest(id: number, outcome: string): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/close`, { outcome });
  }
  reopenPrivateRequest(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/reopen`, {});
  }
  deletePrivateRequest(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/private-requests/${id}`);
  }
  addPrivateRequestLines(id: number, lines: LineInput[], mode: LinesMode = 'APPEND'): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/lines`, { lines, mode });
  }
  parsePrivateRequestLines(id: number, text: string): Observable<ParsedLine[]> {
    return this.http.post<ParsedLine[]>(`${this.base}/private-requests/${id}/lines/parse`, { text });
  }
  updatePrivateRequestLine(id: number, lotId: number, field: PrLineField, value: string | null): Observable<PrLine> {
    return this.http.patch<PrLine>(`${this.base}/private-requests/${id}/lines/${lotId}`, { field, value });
  }
  deletePrivateRequestLine(id: number, lotId: number): Observable<PrView> {
    return this.http.delete<PrView>(`${this.base}/private-requests/${id}/lines/${lotId}`);
  }
  setPrivateRequestLineRegistry(id: number, lotId: number, action: RegAction, regNumber?: string): Observable<PrLine> {
    return this.http.post<PrLine>(`${this.base}/private-requests/${id}/lines/${lotId}/registry`,
      { action, regNumber: regNumber ?? null });
  }
  getPrivateRequestLineCandidates(id: number, lotId: number): Observable<RegistryRecord[]> {
    return this.http.get<RegistryRecord[]>(`${this.base}/private-requests/${id}/lines/${lotId}/registry-candidates`);
  }
  checkPrivateRequestRegistry(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/registry-check`, {});
  }
  importPrivateRequestLines(id: number, body: { mappings: any[]; lines: any[]; mode: LinesMode }): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/lines/import`, body);
  }
  getPrivateRequestSourcing(id: number): Observable<Sourcing> {
    return this.http.get<Sourcing>(`${this.base}/private-requests/${id}/sourcing`);
  }
  previewRfq(id: number, body: RfqPreviewBody): Observable<{ subject: string; body: string }> {
    return this.http.post<{ subject: string; body: string }>(`${this.base}/private-requests/${id}/rfq/preview`, body);
  }
  sendRfq(id: number, body: RfqSendBody): Observable<SendResult[]> {
    return this.http.post<SendResult[]>(`${this.base}/private-requests/${id}/rfq/send`, body);
  }
  /** Документ запроса до отправки («№ —»): Word или PDF; имя файла — в Content-Disposition; только blob (§14). */
  downloadRfqDocument(id: number, body: RfqPreviewBody, format: DocFormat = 'docx'): Observable<HttpResponse<Blob>> {
    return this.http.post(`${this.base}/private-requests/${id}/rfq/document?format=${format}`, body,
      { responseType: 'blob', observe: 'response' });
  }
  /** Документ записанного запроса, с его номером: PDF (переслать в WhatsApp) или Word. */
  downloadPriceRequestDocument(priceRequestId: number, format: DocFormat): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/price-requests/${priceRequestId}/document?format=${format}`,
      { responseType: 'blob', observe: 'response' });
  }
  getPrivateRequestPrices(id: number): Observable<Prices> {
    return this.http.get<Prices>(`${this.base}/private-requests/${id}/prices`);
  }
  searchSiteProducts(q: string): Observable<SiteProduct[]> {
    return this.http.get<SiteProduct[]>(`${this.base}/site-products?q=${encodeURIComponent(q)}`);
  }

  previewImport(file: File): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/private-requests/import/preview`, fd);
  }

  commitImport(body: any): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/import/commit`, body);
  }

  // === Входящие письма ===
  /** mailbox — только письма этого ящика; пусто — оба. */
  getInbound(mailbox?: string): Observable<any[]> {
    const p: any = {};
    if (mailbox) p.mailbox = mailbox;
    return this.http.get<any[]>(`${this.base}/inbound`, { params: p });
  }
  getInboundMailboxes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/inbound/mailboxes`);
  }
  pollInbound(): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/poll`, {});
  }
  previewInbound(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/${id}/preview`, {});
  }
  markInboundProcessed(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/${id}/processed`, {});
  }

  // === Обращения (заявки с сайтов, звонки, WhatsApp) ===
  getLeads(params: { status?: string; channel?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.status) p.status = params.status;
    if (params.channel) p.channel = params.channel;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/leads`, { params: p });
  }
  getLead(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/leads/${id}`);
  }
  getLeadCount(status = 'NEW'): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.base}/leads/count`, { params: { status } });
  }
  createLead(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/leads`, body);
  }
  updateLeadItems(id: number, items: any[]): Observable<any> {
    return this.http.put<any>(`${this.base}/leads/${id}/items`, { items });
  }
  takeLead(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/take`, {});
  }
  closeLead(id: number, reason: string, comment: string): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/close`, { reason, comment });
  }
  reopenLead(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/reopen`, {});
  }
  /** «Не клиент» (обращение из почты): закрыть и больше не заводить обращения из писем этого адреса. */
  markLeadNotClient(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/not-client`, {});
  }
  addLeadEvent(id: number, body: { type: 'NOTE' | 'CALL'; direction?: 'IN' | 'OUT'; body: string }): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/events`, body);
  }
  /** Пустое тело — «Создать заявку» одной кнопкой (спека 2026-10-12 §4). */
  convertLead(id: number, body: any = {}): Observable<{ privateRequestId: number; number: string }> {
    return this.http.post<{ privateRequestId: number; number: string }>(`${this.base}/leads/${id}/convert`, body);
  }
  getLeadSyncStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/leads/sync-status`);
  }
  runLeadSync(): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/sync`, {});
  }

  // === Чаты WhatsApp (спека 2026-09-28-whatsapp-chats-green-api) ===
  getChats(params: { filter?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.filter) p.filter = params.filter;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/chats`, { params: p });
  }
  getChat(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/chats/${id}`);
  }
  getChatMessages(id: number, before?: number): Observable<any[]> {
    const p: any = {};
    if (before != null) p.before = before;
    return this.http.get<any[]>(`${this.base}/chats/${id}/messages`, { params: p });
  }
  /** Только так (blob через HttpClient): голый <img src> не несёт X-Market → 404 на чат KZ. */
  getChatAttachment(chatId: number, attachmentId: number): Observable<Blob> {
    return this.http.get(`${this.base}/chats/${chatId}/attachments/${attachmentId}`, { responseType: 'blob' });
  }
  previewChatAttachment(chatId: number, attachmentId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/attachments/${attachmentId}/preview`, {});
  }
  createChatLead(chatId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/lead`, {});
  }
  setChatNotClient(chatId: number, value: boolean): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/not-client`, { value });
  }
  getWhatsappStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/chats/status`);
  }
  // === WhatsApp: сессия шлюза — «Система → WhatsApp» (только ADMIN; спека 2026-09-28-whatsapp-waha §7) ===
  getWhatsappSession(): Observable<any> {
    return this.http.get<any>(`${this.base}/whatsapp/session`);
  }
  /** QR — только blob через HttpClient: голый <img src> на /api не несёт ни сессии перехватчиков, ни обработки ошибок. */
  getWhatsappQr(): Observable<Blob> {
    return this.http.get(`${this.base}/whatsapp/session/qr`, { responseType: 'blob' });
  }
  restartWhatsappSession(): Observable<any> {
    return this.http.post<any>(`${this.base}/whatsapp/session/restart`, {});
  }
  logoutWhatsappSession(): Observable<any> {
    return this.http.post<any>(`${this.base}/whatsapp/session/logout`, {});
  }
  getLeadChatMessages(leadId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/leads/${leadId}/chat-messages`);
  }
  importLeadItems(leadId: number, body: { mappings: any[]; items: any[]; mode: 'REPLACE' | 'APPEND' }): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${leadId}/items/import`, body);
  }

  // === Калитка ais.westmed.kz: устройства (только ADMIN) ===
  getDevices(): Observable<any> {
    return this.http.get<any>(`${this.base}/devices`);
  }
  getDevicePendingCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.base}/devices/pending-count`);
  }
  approveDevice(id: number, label: string | null): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/approve`, { label });
  }
  rejectDevice(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/reject`, {});
  }
  revokeDevice(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/revoke`, {});
  }

  // === Вход по ключу (passkeys): свои ключи — «Мой профиль»; домен ключей — для кнопки входа ===
  getPasskeys(): Observable<{ keys: any[]; suggestedLabel: string }> {
    return this.http.get<{ keys: any[]; suggestedLabel: string }>(`${this.base}/passkeys`);
  }
  deletePasskey(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/passkeys/${encodeURIComponent(id)}`);
  }
  getPasskeyConfig(): Observable<{ rpId: string }> {
    return this.http.get<{ rpId: string }>(`${this.base}/auth/passkey-config`);
  }

  // === Шаблон письма КП ===
  getEmailTemplate(): Observable<any> {
    return this.http.get<any>(`${this.base}/email-template`);
  }
  getEmailTemplateDefault(): Observable<any> {
    return this.http.get<any>(`${this.base}/email-template/default`);
  }
  saveEmailTemplate(body: { subject: string; body: string; rfqTitle?: string; rfqIntro?: string; rfqAsk?: string;
                            rfqClientLine?: string }): Observable<any> {
    return this.http.put<any>(`${this.base}/email-template`, body);
  }

  // === КП клиенту: реквизиты и печать (спека 2026-10-02-client-kp-constructor §7) ===
  getCompanyProfile(): Observable<any> {
    return this.http.get<any>(`${this.base}/company-profile`);
  }
  saveCompanyProfile(body: any): Observable<any> {
    return this.http.put<any>(`${this.base}/company-profile`, body);
  }
  uploadCompanyImage(kind: string, file: File, removeBackground: boolean): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/company-profile/images/${kind}`, fd,
      { params: { removeBackground: String(removeBackground) } });
  }
  /** Только blob через HttpClient: голый <img src> на /api не несёт X-Market (CLAUDE.md §14). */
  getCompanyImage(kind: string): Observable<Blob> {
    return this.http.get(`${this.base}/company-profile/images/${kind}`, { responseType: 'blob' });
  }
  deleteCompanyImage(kind: string): Observable<any> {
    return this.http.delete<any>(`${this.base}/company-profile/images/${kind}`);
  }
  getCompanyProfileSample(): Observable<PreviewPages> {
    return this.http.get<PreviewPages>(`${this.base}/company-profile/sample-preview`);
  }

  // === КП клиенту (спека §10) ===
  getClientOffers(params: { status?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.status) p.status = params.status;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/client-offers`, { params: p });
  }
  createClientOffer(): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers`, {});
  }
  /** Каталог своего рынка по названию и производителю (спека 2026-10-13 §2.4). */
  searchEquipment(q: string, limit = 8): Observable<EquipmentHit[]> {
    return this.http.get<EquipmentHit[]>(`${this.base}/equipment/search`, { params: { q, limit } });
  }

  /** Подсказки РУ для строк КП — ничего не пишет (спека 2026-10-13 §2.3). */
  getRegistrySuggestions(rows: SuggestionRow[]): Observable<RegistrySuggestion[]> {
    return this.http.post<RegistrySuggestion[]>(`${this.base}/client-offers/registry-suggestions`, { rows });
  }

  getClientOffer(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/client-offers/${id}`);
  }
  saveClientOffer(id: number, body: any): Observable<any> {
    return this.http.put<any>(`${this.base}/client-offers/${id}`, body);
  }
  deleteClientOffer(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/client-offers/${id}`);
  }
  duplicateClientOffer(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${id}/duplicate`, {});
  }
  setClientOfferStatus(id: number, status: string): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${id}/status`, { status });
  }
  /** crowded — таблица КП не влезла обычным кеглем, шрифт уменьшен: редактор подсказывает «Альбомная». */
  getClientOfferPreview(id: number): Observable<PreviewPages> {
    return this.http.get<PreviewPages>(`${this.base}/client-offers/${id}/preview`);
  }
  downloadClientOfferPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/client-offers/${id}/pdf`, { responseType: 'blob' });
  }
  downloadClientOfferDocx(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/client-offers/${id}/docx`, { responseType: 'blob' });
  }
  /** КП клиенту из частной заявки (спека 2026-10-12 §8). */
  createClientOfferFromRequest(requestId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/from-private-request/${requestId}`, {});
  }
  getClientOfferItemOffers(offerId: number, itemId: number): Observable<SupplierOffer[]> {
    return this.http.get<SupplierOffer[]>(`${this.base}/client-offers/${offerId}/items/${itemId}/offers`);
  }
  getClientOfferNewPrices(offerId: number): Observable<{ lines: NewPriceLine[] }> {
    return this.http.get<{ lines: NewPriceLine[] }>(`${this.base}/client-offers/${offerId}/new-prices`);
  }
  applyClientOfferNewPrices(offerId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${offerId}/apply-new-prices`, {});
  }
}
