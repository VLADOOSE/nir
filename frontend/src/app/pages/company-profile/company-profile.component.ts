import { ChangeDetectorRef, Component, DestroyRef, NgZone, OnDestroy, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import { OfferTermsEditorComponent } from '../../shared/offer-terms-editor.component';
import { OfferColumnsEditorComponent } from '../../shared/offer-columns-editor.component';
import { numText, parseNum, vatLabel } from '../../shared/client-offer';

type ImageKind = 'logo' | 'stamp' | 'signature';

/** Предел сервера (ImageProcessor.MAX_BYTES). Проверяем до запроса: сервер ответил бы 400, а выше ~10 МБ — 500 multipart. */
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
/**
 * До какой длинной стороны страница сама уменьшает картинку перед загрузкой, px: фото печати с телефона (24 Мп — 5712 px)
 * больше предела сервера 5000 px (ImageProcessor.MAX_SIDE_IN) и часто больше 5 МБ. Сервер всё равно ужимает до 1200 px.
 */
const CLIENT_MAX_SIDE = 2400;
/** Ставка НДС: 0 ≤ ставка < 100, не больше двух знаков после запятой (подсказки ставок на сервере — NUMERIC(5,2)). */
const RATE_TEXT = /^\d{1,2}([.,]\d{1,2})?$/;

/** «Система → Реквизиты и печать» (спека §7): бланк, подписант, картинки, ставки НДС, умолчания нового КП, образец. */
@Component({
  selector: 'app-company-profile',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, OfferTermsEditorComponent, OfferColumnsEditorComponent],
  template: `
    <div class="cp" *ngIf="p; else loadingTpl">
      <div class="page-head">
        <div>
          <h2>Реквизиты и печать</h2>
          <p class="subtitle">{{ marketLabel }} — бланк, подпись и умолчания нового КП. Меняется только активный рынок.</p>
        </div>
        <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
      </div>

      <div class="cp-grid">
        <div class="cp-forms">
          <section class="card">
            <h3>Бланк</h3>
            <div class="grid2">
              <label>Краткое название <input [(ngModel)]="p.shortName" placeholder="ТОО «West-Med»" /></label>
              <label>Полное название <input [(ngModel)]="p.fullName" /></label>
              <label>Над логотипом слева <textarea rows="3" [(ngModel)]="p.headerLeft"></textarea></label>
              <label>Над логотипом справа <textarea rows="3" [(ngModel)]="p.headerRight"></textarea></label>
              <label>Название вместо логотипа <input [(ngModel)]="p.brandText" /></label>
              <label>БИН / ИНН <input [(ngModel)]="p.binInn" /></label>
              <label class="wide">Строка идентификаторов <input [(ngModel)]="p.idsLine" placeholder="РНН … БИН …" /></label>
              <label class="wide">Адрес <textarea rows="3" [(ngModel)]="p.address"></textarea></label>
              <label class="wide">Счета <textarea rows="2" [(ngModel)]="p.accounts"></textarea></label>
              <label>Банк <input [(ngModel)]="p.bankName" /></label>
              <label>БИК <input [(ngModel)]="p.bik" /></label>
              <label>Телефон <input [(ngModel)]="p.phone" /></label>
              <label>Почта <input [(ngModel)]="p.email" /></label>
            </div>
          </section>

          <section class="card">
            <h3>Подписант</h3>
            <div class="grid2">
              <label>Должность <input [(ngModel)]="p.directorTitle" placeholder="Директор" /></label>
              <label>ФИО <input [(ngModel)]="p.directorName" /></label>
              <label class="wide">Контакты под подписью <input [(ngModel)]="p.signoffContacts" /></label>
            </div>
          </section>

          <section class="card">
            <h3>Логотип, печать, подпись</h3>
            <p class="hint">Скан на белом листе: белый фон уберётся сам. Готовый PNG с прозрачностью не меняется. PNG или JPEG до 5 МБ;
              большое фото с телефона страница уменьшит сама.</p>
            <div class="images">
              <div class="img-card" *ngFor="let k of kinds">
                <div class="img-title">{{ k.label }}</div>
                <div class="img-box">
                  <img *ngIf="images[k.kind]" [src]="images[k.kind]" [alt]="k.label" />
                  <span class="img-empty" *ngIf="!images[k.kind]">{{ !hasImage(k.kind) ? 'не загружено' : imageFailed[k.kind] ? 'не удалось показать' : 'загружаю…' }}</span>
                </div>
                <!-- три одинаковых набора: имя у каждого — с названием картинки («Загрузить: Печать»), иначе диктор их не различит -->
                <label class="check"><input type="checkbox" [(ngModel)]="removeBg[k.kind]" [attr.aria-label]="'Убрать белый фон: ' + k.label" />
                  убрать белый фон</label>
                <div class="img-actions">
                  <input #file type="file" accept="image/png,image/jpeg" (change)="upload(k.kind, $event)" hidden />
                  <button type="button" class="btn btn-line" [disabled]="uploading !== null" (click)="file.click()"
                          [attr.aria-label]="uploadLabel(k.kind) + ': ' + k.label">{{ uploadLabel(k.kind) }}</button>
                  <button type="button" class="btn btn-line" *ngIf="hasImage(k.kind)" [disabled]="uploading !== null"
                          (click)="removeImage(k.kind, k.label)" [attr.aria-label]="'Удалить: ' + k.label">Удалить</button>
                </div>
              </div>
            </div>
            <label class="narrow">Диаметр печати, мм <input type="number" min="20" max="60" [(ngModel)]="p.stampSizeMm" /></label>
          </section>

          <section class="card">
            <h3>НДС</h3>
            <p class="hint">Ставки рынка; ниже — какую ставку ставить новой строке и что подсказывать по регистрации.</p>
            <div class="rates">
              <span class="rate" *ngFor="let r of p.vatRates; let i = index">{{ vatLabel(r) }}
                <button type="button" (click)="removeRate(i)" [attr.aria-label]="'Убрать ставку ' + vatLabel(r)" title="Убрать">×</button></span>
            </div>
            <div class="rate-add">
              <input inputmode="decimal" placeholder="Ставка, %" [(ngModel)]="newRate" (keydown.enter)="addRate()" aria-label="Новая ставка, %" />
              <button type="button" class="btn btn-line" (click)="addRate()">Добавить</button>
              <button type="button" class="btn btn-line" *ngIf="!hasNoVat()" (click)="addNoVat()">+ Без НДС</button>
            </div>
            <div class="grid3">
              <label>Новая строка
                <select [(ngModel)]="p.vatDefault"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
              <label>Подтверждено РУ
                <select [(ngModel)]="p.vatRegistered"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
              <label>«Не подлежит регистрации»
                <select [(ngModel)]="p.vatNotRegistrable"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
            </div>
          </section>

          <section class="card">
            <h3>Новое КП по умолчанию</h3>
            <div class="grid3">
              <label>Наценка, % <input inputmode="decimal" [value]="numText(p.defaultMarkupPct)" (change)="setDefaultMarkup($event)" /></label>
              <label>Следующий «исх. №» <input type="number" min="1" step="1" [(ngModel)]="p.nextNumber" /></label>
              <label>Условия
                <select [(ngModel)]="p.defaultTermsStyle">
                  <option value="LIST">списком под итогом</option><option value="TABLE">таблицей над позициями</option><option value="NONE">не печатать</option>
                </select></label>
            </div>
            <label class="wide intro">Вводная фраза <textarea rows="2" [(ngModel)]="p.defaultIntro"></textarea></label>
            <h4>Условия</h4>
            <app-offer-terms-editor [terms]="p.defaultTerms"></app-offer-terms-editor>
            <h4>Колонки таблицы</h4>
            <app-offer-columns-editor [columns]="p.defaultColumns"></app-offer-columns-editor>
          </section>

          <div class="form-actions">
            <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
          </div>
        </div>

        <aside class="cp-sample card">
          <h3>Образец</h3>
          <p class="hint">Первая страница КП с этими реквизитами — обновляется после сохранения и загрузки картинок.</p>
          <img *ngIf="sample" [src]="sample" alt="Образец первой страницы КП" />
          <p class="hint" *ngIf="sampleLoading">Собираю образец…</p>
          <p class="err" *ngIf="sampleError && !sampleLoading">Образец не собрался — попробуйте ещё раз.</p>
          <button type="button" class="btn btn-line" [disabled]="sampleLoading" (click)="loadSample()">Обновить образец</button>
        </aside>
      </div>
    </div>
    <ng-template #loadingTpl><p class="empty">{{ loadError || 'Загрузка…' }}</p></ng-template>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .cp-grid { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
    .cp-forms { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .card h3 { margin: 0 0 10px; font-size: 15px; }
    .card h4 { margin: 14px 0 6px; font-size: 13px; color: var(--text-muted); }
    /* minmax(0, …): иначе дорожка не ужимается меньше min-content поля — длинный пункт селекта выдавливал его за карточку */
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 12px; margin-top: 10px; align-items: end; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.wide { grid-column: 1 / -1; }
    label.intro { margin-top: 10px; }
    label.narrow { max-width: 220px; margin-top: 10px; }
    label.check { flex-direction: row; align-items: center; gap: 6px; color: var(--text); font-size: 13px; }
    input:not([type="checkbox"]), textarea, select { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    textarea { resize: vertical; }
    .hint { font-size: 12px; color: var(--text-muted); margin: 0 0 8px; }
    .err { font-size: 12px; color: var(--danger-text); margin: 0 0 8px; }
    .images { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
    .img-card { border: 1px solid var(--border); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .img-title { font-weight: 600; font-size: 13px; color: var(--text); }
    /* Подложка «лист»: печать и подпись ложатся на белую бумагу, поэтому она светлая в ЛЮБОЙ теме — на тёмной
       подложке чёрная подпись с убранным фоном пропала бы. Клетка показывает, что белый фон действительно убран.
       Токены --paper (лист) и --paper-ink (чернила) объявлены в styles.scss только на :root и тёмной темой не
       переопределяются — поэтому «лист» светлый и в тёмной теме. */
    .img-box { height: 110px; display: flex; align-items: center; justify-content: center; border-radius: 6px;
      border: 1px solid var(--border); background-color: var(--paper); background-size: 16px 16px;
      background-image: conic-gradient(color-mix(in srgb, var(--paper-ink) 7%, var(--paper)) 25%, transparent 0 50%,
                                       color-mix(in srgb, var(--paper-ink) 7%, var(--paper)) 0 75%, transparent 0); }
    .img-box img { max-width: 100%; max-height: 100px; }
    .img-empty { font-size: 12px; color: color-mix(in srgb, var(--paper-ink) 62%, var(--paper)); }
    .img-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .rates { display: flex; flex-wrap: wrap; gap: 6px; }
    .rate { display: inline-flex; align-items: center; gap: 4px; padding: 3px 4px 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 600;
            background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .rate button { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 15px; }
    .rate-add { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
    .rate-add input { width: 120px; }
    .cp-sample { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); }
    .cp-sample img { width: 100%; border: 1px solid var(--border); margin-bottom: 8px; }
    @media (max-width: 1100px) {
      .cp-grid { grid-template-columns: 1fr; }
      .cp-sample { position: static; }
    }
    @media (max-width: 900px) {
      .grid2, .grid3, .images { grid-template-columns: 1fr; }
      label.narrow { max-width: none; }
      /* 16px — iOS не зумит поле при фокусе; локальные 14px сильнее глобального правила, поэтому повтор здесь */
      input:not([type="checkbox"]), textarea, select { font-size: 16px; }
      .rate-add input { flex: 1 1 120px; }
      /* тач-цели 40 px: галочка «убрать белый фон» — вся подпись, «×» ставки — во всю ширину цели */
      label.check { min-height: 40px; }
      .rate button { min-width: 40px; }
    }
  `],
})
export class CompanyProfileComponent implements OnInit, OnDestroy {
  p: any = null;
  loadError = '';
  saving = false;
  uploading: ImageKind | null = null;
  newRate = '';
  sample: string | null = null;
  sampleLoading = false;
  sampleError = false;
  images: Record<ImageKind, string | null> = { logo: null, stamp: null, signature: null };
  imageFailed: Record<ImageKind, boolean> = { logo: false, stamp: false, signature: false };
  removeBg: Record<ImageKind, boolean> = { logo: false, stamp: true, signature: true };
  readonly kinds: { kind: ImageKind; label: string }[] = [
    { kind: 'logo', label: 'Логотип' }, { kind: 'stamp', label: 'Печать' }, { kind: 'signature', label: 'Подпись' },
  ];
  vatLabel = vatLabel;
  numText = numText;

  /**
   * «Следующий исх. №» в том виде, в каком его последним отдал сервер. Поле уходит в PUT, только если администратор
   * его изменил: счётчик двигают и КП, созданные в других вкладках, — страница, открытая раньше, откатила бы его назад.
   */
  private loadedNextNumber: number | null = null;
  private sampleSub: Subscription | null = null;
  private imageSubs: Partial<Record<ImageKind, Subscription>> = {};

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private market: MarketService, private profiles: CompanyProfileService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef, private zone: NgZone) {}

  get marketLabel() { return this.market.companyLabel(); }

  ngOnInit() {
    this.api.getCompanyProfile().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.setProfile(p); this.loadImages(); this.loadSample(); this.cdr.detectChanges(); },
      error: e => { this.loadError = 'Не удалось загрузить реквизиты: ' + errorText(e); this.cdr.detectChanges(); },
    });
  }

  /** Незавершённые запросы снимает takeUntilDestroyed; адреса уже показанных картинок освобождаем здесь. */
  ngOnDestroy() {
    for (const k of this.kinds) this.revoke(k.kind);
  }

  uploadLabel(kind: ImageKind): string {
    return this.uploading === kind ? 'Загружаю…' : this.hasImage(kind) ? 'Заменить' : 'Загрузить';
  }

  hasImage(kind: ImageKind): boolean {
    return kind === 'logo' ? !!this.p.hasLogo : kind === 'stamp' ? !!this.p.hasStamp : !!this.p.hasSignature;
  }

  /** По change, а не на каждый символ: иначе «20,» превращалось бы в 20 посреди ввода «20,5». */
  setDefaultMarkup(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null || v < -100 || v > 1000) {
      if (v != null) this.notify.error('Наценка — от −100 до 1000%');
      input.value = numText(this.p.defaultMarkupPct);
      return;
    }
    this.p.defaultMarkupPct = v;
  }

  save() {
    const body = this.requestBody();
    if (!body) return;
    this.saving = true;
    this.api.saveCompanyProfile(body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => {
        this.setProfile(p);
        this.saving = false;
        this.profiles.invalidate();
        this.notify.success('Реквизиты сохранены');
        this.loadSample();
        this.cdr.detectChanges();
      },
      error: e => { this.saving = false; this.notify.error('Не сохранено: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  /**
   * Большое фото сперва уменьшается здесь (shrinkImage), затем — предел 5 МБ и загрузка. Исход промиса — через .then, а
   * запрос — в зоне Angular (CLAUDE.md §14: код после нативного промиса может идти мимо зоны — тосты бы не появились).
   */
  upload(kind: ImageKind, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.uploading = kind;
    this.cdr.detectChanges();
    shrinkImage(file, CLIENT_MAX_SIDE).then(r => this.zone.run(() => {
      if (r.file.size > MAX_IMAGE_BYTES) {
        this.uploading = null;
        this.notify.error('Файл больше 5 МБ — уменьшите скан');
        this.cdr.detectChanges();
        return;
      }
      this.api.uploadCompanyImage(kind, r.file, this.removeBg[kind]).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: p => {
          this.uploading = null;
          this.applyImageResponse(p);
          this.loadImage(kind);
          this.profiles.invalidate();
          this.loadSample();
          this.notify.success(r.resized ? 'Картинка загружена — фото уменьшено до ' + CLIENT_MAX_SIDE + ' px' : 'Картинка загружена');
          this.cdr.detectChanges();
        },
        error: e => { this.uploading = null; this.notify.error('Не загружено: ' + errorText(e)); this.cdr.detectChanges(); },
      });
    }));
  }

  removeImage(kind: ImageKind, label: string) {
    this.confirm.ask(`Удалить «${label}»?`, 'Из документов, выгруженных после этого, картинка пропадёт; уже скачанные файлы не меняются.',
      { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteCompanyImage(kind).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: p => {
            this.applyImageResponse(p);
            this.imageSubs[kind]?.unsubscribe();
            this.revoke(kind);
            this.profiles.invalidate();
            this.loadSample();
            this.cdr.detectChanges();
          },
          error: e => { this.notify.error('Не удалено: ' + errorText(e)); this.cdr.detectChanges(); },
        });
      });
  }

  addRate() {
    const text = (this.newRate || '').replace(/[\s%]/g, '');   // \s — и неразрывные пробелы
    const v = RATE_TEXT.test(text) ? parseNum(text) : null;
    if (v == null) { this.notify.error('Ставка НДС — от 0 до 99,99%: меньше 100, не больше двух знаков после запятой'); return; }
    if (this.p.vatRates.some((r: number | null) => r === v)) { this.notify.error('Такая ставка уже есть'); return; }
    this.p.vatRates = [...this.p.vatRates, v];
    this.newRate = '';
  }

  addNoVat() {
    this.p.vatRates = [...this.p.vatRates, null];
  }

  hasNoVat(): boolean {
    return this.p.vatRates.some((r: number | null) => r == null);
  }

  removeRate(i: number) {
    this.p.vatRates = this.p.vatRates.filter((_: any, idx: number) => idx !== i);
  }

  loadSample() {
    this.sampleSub?.unsubscribe();   // ответ на устаревший запрос не должен перекрыть свежий образец
    this.sampleLoading = true;
    this.sampleError = false;
    this.sampleSub = this.api.getCompanyProfileSample().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        this.sample = r.pages.length ? 'data:image/png;base64,' + r.pages[0] : null;
        this.sampleLoading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.sampleLoading = false; this.sampleError = true; this.cdr.detectChanges(); },
    });
  }

  /** Тело PUT — поля CompanyProfileRequest; «следующий исх. №» — только если администратор его изменил. */
  private requestBody(): any | null {
    const p = this.p;
    const body: any = {
      shortName: p.shortName, fullName: p.fullName, headerLeft: p.headerLeft, headerRight: p.headerRight,
      brandText: p.brandText, idsLine: p.idsLine, binInn: p.binInn, address: p.address, accounts: p.accounts,
      bankName: p.bankName, bik: p.bik, phone: p.phone, email: p.email,
      directorTitle: p.directorTitle, directorName: p.directorName, signoffContacts: p.signoffContacts,
      stampSizeMm: p.stampSizeMm, vatRates: p.vatRates, vatDefault: p.vatDefault, vatRegistered: p.vatRegistered,
      vatNotRegistrable: p.vatNotRegistrable, defaultMarkupPct: p.defaultMarkupPct, defaultColumns: p.defaultColumns,
      defaultTerms: p.defaultTerms, defaultTermsStyle: p.defaultTermsStyle, defaultIntro: p.defaultIntro,
    };
    const n = p.nextNumber;
    if (n != null && n !== this.loadedNextNumber) {
      if (!Number.isInteger(n) || n < 1) { this.notify.error('Следующий «исх. №» — целое число от 1'); return null; }
      body.nextNumber = n;
    }
    return body;
  }

  private setProfile(p: any) {
    this.p = p;
    this.loadedNextNumber = p.nextNumber;
  }

  /**
   * Ответ на загрузку/удаление картинки — весь профиль. Берём флаги картинок и свежий счётчик номеров, остальные поля
   * не трогаем: несохранённые правки формы не должны пропасть. Правленый администратором номер остаётся его.
   */
  private applyImageResponse(p: any) {
    this.p.hasLogo = p.hasLogo;
    this.p.hasStamp = p.hasStamp;
    this.p.hasSignature = p.hasSignature;
    this.p.imagesUpdatedAt = p.imagesUpdatedAt;
    if (this.p.nextNumber === this.loadedNextNumber) this.p.nextNumber = p.nextNumber;
    this.loadedNextNumber = p.nextNumber;
  }

  private loadImages() {
    for (const k of this.kinds) if (this.hasImage(k.kind)) this.loadImage(k.kind);
  }

  /** Картинка — только blob через HttpClient (X-Market, §14); прежний адрес освобождаем. */
  private loadImage(kind: ImageKind) {
    this.imageSubs[kind]?.unsubscribe();
    this.imageFailed[kind] = false;
    this.imageSubs[kind] = this.api.getCompanyImage(kind).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: blob => { this.revoke(kind); this.images[kind] = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => { this.imageFailed[kind] = true; this.cdr.detectChanges(); },
    });
  }

  private revoke(kind: ImageKind) {
    const url = this.images[kind];
    if (url) URL.revokeObjectURL(url);
    this.images[kind] = null;
  }
}

/**
 * Картинка длиннее maxSide по большей стороне → уменьшенная копия, повёрнутая по EXIF (createImageBitmap
 * с imageOrientation 'from-image': фото с телефона иначе легло бы на бок — сервер EXIF выбрасывает). PNG остаётся PNG
 * (прозрачность), остальное — JPEG 0,92. Не длиннее предела, браузер не умеет или файл не картинка — как есть: решит сервер.
 */
function shrinkImage(file: File, maxSide: number): Promise<{ file: File; resized: boolean }> {
  const asIs = { file, resized: false };
  if (typeof createImageBitmap !== 'function') return Promise.resolve(asIs);
  return createImageBitmap(file, { imageOrientation: 'from-image' }).then(bitmap => {
    const longSide = Math.max(bitmap.width, bitmap.height);
    const canvas = longSide > maxSide ? document.createElement('canvas') : null;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) { bitmap.close(); return asIs; }
    const k = maxSide / longSide;
    canvas.width = Math.max(1, Math.round(bitmap.width * k));
    canvas.height = Math.max(1, Math.round(bitmap.height * k));
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const png = file.type === 'image/png';
    return new Promise<{ file: File; resized: boolean }>(resolve => canvas.toBlob(blob => {
      if (!blob) { resolve(asIs); return; }
      const name = file.name.replace(/\.[^.]*$/, '') + (png ? '.png' : '.jpg');
      resolve({ file: new File([blob], name, { type: blob.type }), resized: true });
    }, png ? 'image/png' : 'image/jpeg', 0.92));
  }).catch(() => asIs);
}

function errorText(e: any): string {
  const errors = e?.error?.errors;
  if (errors && typeof errors === 'object') {
    const first = Object.values(errors)[0];
    if (first) return String(first);
  }
  return e?.error?.message || e?.message || 'ошибка';
}
