import { Component, ChangeDetectorRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

/**
 * Панель «Подбор» по лоту: кандидаты реестра НЦЭЛС + комплектность аппаратов.
 * Реестр и комплектность живут вместе: комплектность открывается из реестра по тому же лоту.
 */
@Component({
  selector: 'app-lot-registry-panel',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="lrp" *ngIf="lot">
      <div class="lrp-head">
        <span><b>Реестр НЦЭЛС РК:</b> {{ lot.equipName }}</span>
        <button class="btn btn-cancel" (click)="close.emit()">✕ Закрыть</button>
      </div>
      <div class="lrp-note">Реестр НЦЭЛС — допуск (№ РУ); габариты/вес здесь не хранятся, соответствие — по совпадению наименования.</div>

      <div *ngIf="zone() === 'CANNOT'" class="lrp-cannot">
        <strong>Определить модель по этому лоту нельзя</strong>
        <div>{{ cannotText() }}</div>
      </div>
      <div *ngIf="zone() === 'SHORTLIST'" class="lrp-hint">
        {{ shortlistText() }}
        <!-- techSpecParsed = ПРОДУКТОВЫЙ признак бэка (якорь goszakup ИЛИ персистентный
             techSpecStatus=OK), а не «нашёлся якорь». Это одна из двух поверхностей, где совет
             «разберите ТЗ» показывается, вторая — cannotText()/NEED_TECH_SPEC; обе обязаны
             отвечать на вопрос одинаково. Пока признак был анкерным, все 39 SK-лотов с уже
             разобранным ТЗ получали этот совет — жать «ТЗ» им бессмысленно, файл уже разобран
             фоновой очередью (замер и обоснование — RegistryMatchService.specParsed). -->
        <span *ngIf="!registry?.techSpecParsed && imported"> Разбор техспецификации уточнит подбор — кнопка «ТЗ» в строке лота.</span>
      </div>
      <div *ngIf="registry?.loading" class="lrp-loading">Ищем похожие изделия в реестре…</div>
      <div *ngIf="registry?.error && !registry?.loading" class="lrp-error">
        Реестр недоступен: {{ registry?.error }} — закройте и откройте панель «Подбор», чтобы повторить.
      </div>
      <!-- страховка на случай ответа без зоны: пустой список сам по себе = CANNOT/NO_CANDIDATES, у него свой текст выше -->
      <div *ngIf="zone() && zone() !== 'CANNOT' && !registry?.items?.length" class="lrp-empty">
        Похожих записей в реестре не найдено — вероятно, это не медизделие (услуга/расходник) или нужен другой запрос
      </div>

      <!-- в зоне CANNOT список свёрнут: показывать отобранное как ответ было бы ложью.
           Но и выбрасывать его нельзя (прятать верный ответ — тяжелее, чем показать лишний) → раскрывается по кнопке. -->
      <button class="zero-toggle" *ngIf="zone() === 'CANNOT' && registry?.items?.length"
              (click)="showWeak = !showWeak">
        {{ showWeak ? '▴ скрыть отобранные записи'
                    : '▾ всё равно показать отобранное (' + registry?.items?.length + ') — ненадёжно' }}
      </button>

      <ng-container *ngIf="candidatesVisible()">
      <div class="cand" *ngFor="let c of registry?.items || []">
        <div class="cand-main" (click)="toggleRegistryDetail(c)"
             [title]="registry?.openReg === c.regNumber ? 'Свернуть описание' : 'Показать описание из карточки НЦЭЛС'">
          <div class="cand-row1">
            <span *ngIf="zone() === 'CONFIDENT'" class="score-badge" [class.score-good]="c.score >= confidentMin"
                  title="Данных лота хватило, чтобы измерить совпадение">{{ scorePct(c) }}%</span>
            <span *ngIf="zone() === 'SHORTLIST'" class="score-badge score-name"
                  title="Кандидаты неразличимы по данным лота — процент вводил бы в заблуждение">похожее</span>
            <span *ngIf="zone() === 'CANNOT'" class="score-badge"
                  title="Отобрано поиском, но данных лота не хватает, чтобы этому верить">ненадёжно</span>
            <span class="cand-name">{{ c.name }}</span>
            <span class="cand-chev">{{ registry?.openReg === c.regNumber ? '▴' : '▾' }}</span>
          </div>
          <div class="cand-row2">
            РУ {{ c.regNumber }} · {{ c.producer || '—' }} · {{ c.country || '—' }} ·
            {{ c.unlimited ? 'бессрочно' : (c.expirationDate ? 'до ' + formatDate(c.expirationDate) : '—') }}
          </div>
        </div>
        <!-- в CANNOT кнопка приглушена (паттерн 0%-компонентов комплектности ниже): adopt не косметика —
             он ставит предложенную модель лота, и она уезжает поставщику в письме КП -->
        <div class="cand-actions">
          <button class="btn" [class.btn-adopt]="zone() !== 'CANNOT'" [class.btn-adopt-muted]="zone() === 'CANNOT'"
                  [disabled]="adoptBusy" (click)="adoptFromRegistry(c)"
                  title="Создать модель каталога из этого РУ и предложить лоту">Взять в работу</button>
        </div>

        <div *ngIf="registry?.openReg === c.regNumber" class="cand-detail">
          <div *ngIf="registry?.detailLoading" class="lrp-loading">Загружаем карточку НЦЭЛС…</div>
          <div *ngIf="registry?.detailError && !registry?.detailLoading" class="cand-detail-error">
            {{ registry?.detailError }} — сверните и разверните строку, чтобы повторить.
          </div>
          <div *ngIf="registry?.detail && !registry?.detailLoading" class="cand-detail-cols">
            <div *ngIf="lot?.requiredSpec" class="cand-detail-col">
              <div class="cand-detail-h">ТЗ лота</div>
              <pre class="cand-detail-pre">{{ lot.requiredSpec }}</pre>
            </div>
            <div class="cand-detail-col">
              <div class="cand-detail-h">Из реестра НЦЭЛС</div>
              <div *ngIf="registryDetailEmpty(registry?.detail)" class="lrp-empty">В карточке НЦЭЛС описание не заполнено</div>
              <div *ngIf="registry?.detail?.riskClass || registry?.detail?.miKind" class="cand-detail-meta">
                <span *ngIf="registry?.detail?.riskClass">{{ registry?.detail?.riskClass }}</span>
                <span *ngIf="registry?.detail?.riskClass && registry?.detail?.miKind"> · </span>
                <span *ngIf="registry?.detail?.miKind">{{ registry?.detail?.miKind }}</span>
                <div *ngIf="registry?.detail?.miKindDef" class="cand-detail-def">{{ registry?.detail?.miKindDef }}</div>
              </div>
              <div *ngIf="registry?.detail?.purpose" class="cand-detail-block"><b>Назначение:</b> {{ registry?.detail?.purpose }}</div>
              <div *ngIf="registry?.detail?.useArea" class="cand-detail-block"><b>Область применения:</b> {{ registry?.detail?.useArea }}</div>
              <div *ngIf="registry?.detail?.techChars" class="cand-detail-block"><b>Краткие тех. характеристики:</b>
                <pre class="cand-detail-pre">{{ registry?.detail?.techChars }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
      </ng-container>

      <div class="complect-cta" *ngIf="!complect">
        <button class="btn btn-registry" (click)="openComplect()"
                title="Найти лот в комплектности родительского аппарата (для электродов/пластин/принадлежностей)">
          🔧 Комплектность аппаратов
        </button>
        <span class="lrp-note">Если лот — принадлежность к аппарату (электрод, пластина), допуск может быть в комплектности аппарата.</span>
      </div>

      <div class="complect" *ngIf="complect">
        <div class="lrp-head">
          <span><b>Комплектность аппаратов:</b> {{ lot.equipName }}</span>
          <button class="btn btn-cancel" (click)="closeComplect()">✕ Закрыть</button>
        </div>
        <div class="complect-term">
          <input type="text" [(ngModel)]="complect.term" placeholder="Название аппарата (напр. Элэскулап)"
                 (keyup.enter)="runComplect(complect.term)">
          <button class="btn btn-primary" [disabled]="complect.loading" (click)="runComplect(complect.term)">Искать</button>
        </div>
        <div *ngIf="complect.loading" class="lrp-loading">Ищем в комплектности аппаратов…</div>
        <div *ngIf="!complect.loading && complect.searched && !complect.apparatuses.length" class="lrp-empty">
          Аппарат не найден — уточните его название в поле выше и нажмите «Искать».
        </div>

        <div *ngFor="let a of complect.apparatuses" class="app-box">
          <div class="app-head">{{ a.name }} · <b>{{ a.country || '—' }}</b> · {{ a.producer || '—' }} · РУ {{ a.regNumber }}</div>
          <div *ngIf="!a._relevant.length && !a._zero.length" class="lrp-empty">Комплектность у этого аппарата не заполнена.</div>

          <div class="comp" *ngFor="let comp of a._relevant; let i = index" [class.comp-reco]="i === 0">
            <div class="comp-row1">
              <span class="score-badge" [class.score-good]="i === 0">{{ scorePct(comp) }}%</span>
              <span *ngIf="i === 0" class="reco-chip">★ рекомендуем</span>
            </div>
            <pre class="comp-pre">{{ comp.productName }}</pre>
            <div class="comp-row2">{{ comp.component || '—' }} · {{ comp.country || '—' }}</div>
            <button class="btn" [class.btn-adopt]="i === 0" [class.btn-adopt-muted]="i !== 0" [disabled]="adoptBusy"
                    (click)="adoptComponent(a, comp)"
                    title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button>
          </div>

          <button class="zero-toggle" *ngIf="a._zero.length" (click)="a._showZero = !a._showZero">
            {{ a._showZero ? '▴ скрыть нерелевантные' : '▾ ещё ' + a._zero.length + ' нерелевантных (0%)' }}
          </button>
          <ng-container *ngIf="a._showZero">
            <div class="comp comp-zero" *ngFor="let comp of a._zero">
              <div class="comp-row1"><span class="score-badge">0%</span></div>
              <pre class="comp-pre">{{ comp.productName }}</pre>
              <div class="comp-row2">{{ comp.component || '—' }} · {{ comp.country || '—' }}</div>
              <button class="btn btn-adopt-muted" [disabled]="adoptBusy" (click)="adoptComponent(a, comp)"
                      title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button>
            </div>
          </ng-container>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .lrp { margin: 10px 0 16px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); }
    .lrp-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap; }
    .lrp-note { font-size: 12px; color: var(--text-muted); margin: 4px 0 8px; }
    .lrp-loading { color: var(--text-muted); padding: 6px 0; }
    .lrp-empty { color: var(--text-muted); font-size: 14px; padding: 12px 0; }
    .lrp-hint { background: color-mix(in srgb, var(--warn) 15%, transparent); border-left: 3px solid var(--warn);
                padding: 8px 12px; border-radius: 4px; margin-bottom: 8px; font-size: 13px; color: var(--warn-text); }
    .lrp-error { color: var(--danger-text); font-size: 14px; padding: 8px 0; }
    /* подсветка ОБЛАСТИ (не чип): формула 8%, var(--surface) + семантический border — НЕ --surface-2,
       иначе блок сольётся с фоном панели .lrp и предупреждение перестанет читаться как предупреждение */
    .lrp-cannot { background: color-mix(in srgb, var(--warn) 8%, var(--surface)); border: 1px solid var(--warn);
                  border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; font-size: 13px;
                  color: var(--text); line-height: 1.5; }
    .lrp-cannot strong { display: block; margin-bottom: 4px; color: var(--warn-text); }
    .cand { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); margin-bottom: 8px; padding: 10px 12px; }
    .cand-main { cursor: pointer; }
    .cand-row1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .cand-name { font-size: 14px; color: var(--text); flex: 1; min-width: 0; }
    .cand-chev { color: var(--text-muted); font-size: 11px; }
    .cand-row2 { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
    .cand-actions { margin-top: 8px; }
    .cand-detail { margin-top: 10px; border-top: 1px solid var(--border); padding-top: 10px; }
    .cand-detail-cols { display: flex; gap: 16px; align-items: flex-start; }
    .cand-detail-col { flex: 1; min-width: 0; }
    .cand-detail-h { font-size: 11px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; text-transform: uppercase; letter-spacing: .04em; }
    .cand-detail-pre { white-space: pre-wrap; max-height: 300px; overflow-y: auto; background: var(--surface-2);
                       border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; font: inherit; margin: 4px 0 0; }
    .cand-detail-meta { margin-bottom: 8px; font-weight: 600; color: var(--text); }
    .cand-detail-def { font-size: 12px; color: var(--text-muted); font-weight: 400; margin-top: 2px; }
    .cand-detail-block { margin-bottom: 6px; color: var(--text); }
    .cand-detail-error { color: var(--danger-text); padding: 4px 0; }
    .score-badge { background: var(--surface-2); color: var(--text); border-radius: 8px; padding: 2px 8px; font-size: 12px; white-space: nowrap; }
    .score-badge.score-good { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .score-badge.score-name { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .complect-cta { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
    .complect { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 10px; }
    .complect-term { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
    .complect-term input { flex: 1; min-width: 180px; max-width: 360px; padding: 6px 10px; border: 1px solid var(--border);
                           border-radius: 6px; background: var(--surface); color: var(--text); }
    .app-box { margin: 10px 0; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .app-head { font-size: 13px; margin-bottom: 6px; color: var(--text); }
    .comp { border-top: 1px solid var(--border); padding: 8px 0; }
    .comp-reco { box-shadow: inset 3px 0 0 var(--success); padding-left: 8px; }
    .comp-row1 { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .comp-row2 { font-size: 12px; color: var(--text-muted); margin: 4px 0 6px; }
    .comp-pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 0; font: inherit; color: var(--text); }
    .comp-zero { opacity: .75; }
    .reco-chip { background: var(--success); color: var(--accent-contrast); border-radius: 8px; padding: 1px 7px; font-size: 11px; white-space: nowrap; }
    .zero-toggle { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 12px; padding: 6px 0; }
    .zero-toggle:hover { color: var(--text); text-decoration: underline; }
    .btn-registry { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .btn-adopt { background: #0e9f6e; color: var(--accent-contrast); }
    .btn-adopt-muted { background: var(--surface-2); color: var(--text); }

    @media (max-width: 900px) {
      .cand-detail-cols { flex-direction: column; gap: 10px; }
      .cand-actions .btn,
      .comp .btn,
      .complect-term .btn { width: 100%; }
    }
  `],
})
export class LotRegistryPanelComponent implements OnChanges {
  @Input() lot: any | null = null;
  @Input() imported = false;
  @Output() adopted = new EventEmitter<any>();
  @Output() close = new EventEmitter<void>();

  registry: { loading: boolean; items: any[]; confidence?: string; cannotReason?: string | null;
              techSpecParsed?: boolean; error?: string | null;
              openReg?: string | null; detail?: any; detailLoading?: boolean; detailError?: string | null } | null = null;
  complect: { term: string; loading: boolean; searched: boolean; apparatuses: any[] } | null = null;
  adoptBusy = false;
  /** Оператор раскрыл отобранное в зоне CANNOT (по умолчанию свёрнуто). */
  showWeak = false;
  /** Порог «зелёного» процента = CONFIDENT_MIN бэка (RegistryMatchService, 0.55). Здесь жил 0.35 —
   *  число из прежней шкалы бренд-скоринга, к нынешней оно отношения не имеет. */
  readonly confidentMin = 0.55;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService) {}

  /** Смена лота = новый подбор с нуля (панель переиспользуется между лотами). */
  ngOnChanges(ch: SimpleChanges) {
    if (ch['lot']) {
      this.complect = null;
      if (this.lot) this.loadRegistry(); else this.registry = null;
    }
  }

  loadRegistry() {
    const l = this.lot;
    if (!l) return;
    this.showWeak = false;   // раскрытие ненадёжного не переезжает на следующий лот
    this.registry = { loading: true, items: [], confidence: 'CONFIDENT', techSpecParsed: true };
    this.cdr.detectChanges();
    this.api.getLotRegistryCandidates(l.id).subscribe({
      next: (r: any) => {
        this.registry = {
          loading: false,
          items: r?.candidates || [],
          // без зоны от бэка честнее скромная SHORTLIST (список без процентов), чем выдуманная уверенность
          confidence: r?.confidence || 'SHORTLIST',
          cannotReason: r?.cannotReason || null,
          techSpecParsed: !!r?.techSpecParsed,
        };
        this.cdr.detectChanges();
      },
      error: err => {
        // панель не закрываем — её жизнью управляет родитель; в панели живёт текст ошибки (тост уходит, текст остаётся),
        // подсказка «только по названию» и «ничего не найдено» при error не показываются — они были бы ложью
        this.registry = {
          loading: false,
          items: [],
          error: err.error?.message || err.message || 'Не удалось получить кандидатов',
        };
        this.notify.error('Реестр: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  toggleRegistryDetail(c: any) {
    const p = this.registry;
    if (!p) return;
    if (p.openReg === c.regNumber) { p.openReg = null; this.cdr.detectChanges(); return; }
    p.openReg = c.regNumber;
    p.detail = c._detail || null;
    p.detailError = null;
    p.detailLoading = !p.detail;
    if (!p.detail) {
      this.api.getRegistryDetail(c.regNumber).subscribe({
        next: d => {
          c._detail = d; // фронтовый кеш на объекте кандидата — повторный разворот без запроса
          if (p.openReg === c.regNumber) { p.detail = d; p.detailLoading = false; }
          this.cdr.detectChanges();
        },
        error: err => {
          // ошибка живёт в развороте (панель не закрываем, тост не нужен);
          // detail остаётся null и в c._detail не кешируется → повторное открытие = retry
          if (p.openReg === c.regNumber) {
            p.detailLoading = false;
            p.detailError = err.error?.message || 'Не удалось получить карточку НЦЭЛС';
          }
          this.cdr.detectChanges();
        }
      });
    }
    this.cdr.detectChanges();
  }

  /**
   * Зона честности ответа — но только когда панели есть что показывать: во время загрузки и при
   * ошибке любая зона была бы ложью (ответа ещё/уже нет), поэтому там она null и все три блока молчат.
   */
  zone(): string | null {
    const r = this.registry;
    return !r || r.loading || r.error ? null : (r.confidence || null);
  }

  /** В CANNOT список свёрнут, пока оператор сам его не раскрыл. */
  candidatesVisible(): boolean {
    const z = this.zone();
    return !!z && (z !== 'CANNOT' || this.showWeak);
  }

  /**
   * У SHORTLIST две причины, и они требуют РАЗНЫХ слов (бэк, confidenceOf): либо равноправных
   * записей много (родовой лот), либо кандидат ровно один, но скор не дотянул до уверенности —
   * именно ради него порог SHORTLIST_MIN опускали 0.55 → 0.30. Общий текст «кандидаты похожи
   * между собой, выберите один» на одном кандидате противоречит экрану: выбирать не из чего.
   */
  shortlistText(): string {
    return this.registry?.items?.length === 1
      ? 'Один правдоподобный кандидат, но данных лота не хватило, чтобы за него поручиться, — проверьте сами.'
      : 'Кандидаты похожи между собой — лот описан слишком общо, чтобы выбрать один. Проверьте глазами и выберите сами.';
  }

  /** Причина «нельзя» человеческим языком + действие, которое из неё следует. */
  cannotText(): string {
    switch (this.registry?.cannotReason) {
      case 'NO_CANDIDATES':
        return 'В реестре НЦЭЛС не нашлось ни одной похожей записи. Если лот — принадлежность к аппарату '
             + '(электрод, датчик, пластина), допуск может быть в комплектности аппарата — кнопка ниже.';
      case 'NEED_TECH_SPEC':
        // кнопка «ТЗ» есть только у импортных тендеров (*ngIf="isImportedTender()" у родителя);
        // на ручном KZ-тендере отправлять оператора к несуществующей кнопке нельзя
        return 'Данных лота не хватает, чтобы отличить модели друг от друга. '
             + (this.imported
                 ? 'Разберите техспецификацию — кнопка «ТЗ» в строке лота — и откройте «Подбор» снова.'
                 : 'Заполните «Требования к спецификации» в форме лота («✎ Редактировать» в меню «⋯») '
                   + 'и откройте «Подбор» снова.');
      case 'TECH_SPEC_FAILED':
        return 'Техспецификацию получить не удалось: файла нет на площадке или площадка недоступна. '
             + 'Уточните запрос вручную или ищите изделие по названию в реестре.';
      case 'WEAK_MATCH':
        return 'Техспецификация разобрана, но подходящего в реестре не нашлось — вероятно, изделие '
             + 'не зарегистрировано в НЦЭЛС.';
      case 'QUERY_NOT_IN_REGISTRY':
        return 'Слов из названия лота в реестре нет — поиск шёл по обрывку названия, поэтому найденному '
             + 'верить нельзя. Проверьте название лота или поищите изделие вручную.';
      default:
        return 'Определить по этому лоту нельзя.';
    }
  }

  registryDetailEmpty(d: any): boolean {
    return !!d && !d.riskClass && !d.purpose && !d.useArea && !d.techChars && !d.miKind;
  }

  scorePct(c: any): number { return Math.round((c?.score || 0) * 100); }

  adoptFromRegistry(c: any) {
    const lot = this.lot;
    if (!lot || !c?.regNumber) return;
    this.adoptBusy = true;
    this.api.adoptRegistryForLot(lot.id, c.regNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success(`Модель из реестра предложена для лота: ${c.name}`);
        this.adopted.emit(this.lot); // дальше оркестрирует родитель (перезагрузка лотов + панель КП)
      },
      error: (e) => {
        this.adoptBusy = false;
        this.notify.error(e.error?.message || 'Не удалось взять РУ в работу');
        this.cdr.detectChanges();
      }
    });
  }

  openComplect() {
    if (!this.lot) return;
    this.complect = { term: '', loading: true, searched: false, apparatuses: [] };
    this.cdr.detectChanges();
    // первый прогон — без term: бэк сам извлечёт бренд из ТЗ
    this.runComplect(undefined);
  }

  runComplect(term?: string) {
    const l = this.lot;
    if (!this.complect || !l) return;
    this.complect.loading = true;
    this.cdr.detectChanges();
    this.api.complectSearch(l.id, term).subscribe({
      next: (r: any) => {
        const apparatuses = (r?.apparatuses || []).map((a: any) => ({
          ...a,
          // разделяем на релевантные (есть совпадение) и нерелевантные (0%) — 0% прячем под тоглом
          _relevant: (a.components || []).filter((c: any) => (c.score || 0) > 0),
          _zero: (a.components || []).filter((c: any) => !((c.score || 0) > 0)),
          _showZero: false,
        }));
        this.complect = { term: r?.term || '', loading: false, searched: true, apparatuses };
        this.cdr.detectChanges();
      },
      error: err => {
        if (this.complect) { this.complect.loading = false; this.complect.searched = true; }
        this.notify.error('Комплектность: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  closeComplect() { this.complect = null; this.cdr.detectChanges(); }

  adoptComponent(c: any, comp: any) {
    if (!this.complect || !this.lot) return;
    this.adoptBusy = true;
    this.cdr.detectChanges();
    this.api.adoptComponent(this.lot.id, c.regNumber, comp.partNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success('Компонент взят в работу — предложенная модель лота обновлена');
        this.closeComplect();
        this.adopted.emit(this.lot); // дальше оркестрирует родитель (перезагрузка лотов + панель КП)
      },
      error: err => {
        this.adoptBusy = false;
        this.notify.error('Не удалось взять компонент: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  formatDate(d: string): string {
    if (!d) return '—';
    const dt = new Date(d);
    return isNaN(dt.getTime()) ? d : dt.toLocaleDateString('ru-RU');
  }
}
