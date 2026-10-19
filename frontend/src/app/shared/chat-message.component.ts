import { ChangeDetectorRef, Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { fullDateTime } from './relative-time';

const NOT_STORED: Record<string, string> = {
  TOO_LARGE: 'файл больше предела — смотрите в телефоне',
  GROUP: 'файлы групп не сохраняются — смотрите в телефоне',
  DOWNLOAD_FAILED: 'не удалось скачать — смотрите в телефоне',
};

/** Письмо длиннее — свёрнуто в ленте, целиком — «Показать полностью». */
const LONG_CHARS = 600;
const LONG_LINES = 10;

function isLongText(body: string | null | undefined): boolean {
  return !!body && (body.length > LONG_CHARS || body.split('\n').length > LONG_LINES);
}

/**
 * Сообщение чата пузырём (спека whatsapp-chats §9.2): входящие слева, исходящие справа. Письмо (вид EMAIL, спека
 * info-mail-leads §3) — тема, текст (длинный свёрнут) и все файлы списком: картинка — крупно в странице по нажатию,
 * остальное скачивается. Файлы — ТОЛЬКО через HttpClient blob: голый <img src="/api/…"> не несёт X-Market, и бэкенд
 * ответил бы 404 на чат KZ. Полный размер фото — оверлей в странице (window.open с blob на iOS режется).
 */
@Component({
  selector: 'app-chat-message',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="call" *ngIf="m.type === 'CALL'">
      <span class="call-line">{{ m.body }} · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></span>
    </div>
    <div class="row" *ngIf="m.type === 'EMAIL'" [class.out]="m.direction === 'OUT'">
      <div class="bubble" [class.out]="m.direction === 'OUT'">
        <div class="subj" *ngIf="m.subject">{{ m.subject }}</div>
        <div class="text" *ngIf="m.body" [class.clamped]="isLong && !expanded">{{ m.body }}</div>
        <button type="button" class="linklike more" *ngIf="isLong" [attr.aria-expanded]="expanded"
                (click)="expanded = !expanded">{{ expanded ? 'Свернуть' : 'Показать полностью' }}</button>
        <div class="file" *ngFor="let a of m.attachments || []">
          <ng-container *ngIf="a.stored; else gone">
            <button type="button" class="linklike" (click)="openFile(a)">📎 {{ a.fileName || 'файл' }}<span class="size" *ngIf="a.sizeBytes"> · {{ size(a.sizeBytes) }}</span></button>
            <button type="button" class="btn btn-line btn-sm" *ngIf="a.excel && canParse" (click)="parse.emit(a)">Разобрать в позиции</button>
          </ng-container>
          <ng-template #gone><span class="muted">📎 {{ a.fileName || 'файл' }} — {{ notStored(a.notStoredReason) }}</span></ng-template>
        </div>
        <div class="meta">письмо<ng-container *ngIf="m.direction === 'OUT' && m.senderName"> · {{ m.senderName }}</ng-container> · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></div>
      </div>
    </div>
    <div class="row" *ngIf="m.type !== 'CALL' && m.type !== 'EMAIL'" [class.out]="m.direction === 'OUT'">
      <div class="bubble" [class.out]="m.direction === 'OUT'" [class.deleted]="m.deleted">
        <div class="author" *ngIf="showAuthor && m.direction === 'IN' && m.senderName">{{ m.senderName }}</div>
        <ng-container *ngIf="m.attachment as a">
          <button type="button" class="thumb" *ngIf="a.image" (click)="onThumb()" [attr.aria-label]="'Открыть ' + (a.fileName || 'фото')">
            <img *ngIf="imageUrl" [src]="imageUrl" [alt]="a.fileName || 'фото'" />
            <span *ngIf="!imageUrl" class="thumb-ph">{{ imageFailed ? 'фото не загрузилось — нажмите, чтобы повторить' : 'фото загружается…' }}</span>
          </button>
          <div class="file" *ngIf="a.stored && !a.image">
            <button type="button" class="linklike" (click)="download(a)">📎 {{ a.fileName || 'файл' }}<span class="size" *ngIf="a.sizeBytes"> · {{ size(a.sizeBytes) }}</span></button>
            <button type="button" class="btn btn-line btn-sm" *ngIf="a.excel && canParse" (click)="parse.emit(a)">Разобрать в позиции</button>
          </div>
          <div class="file muted" *ngIf="!a.stored">📎 {{ a.fileName || 'файл' }} — {{ notStored(a.notStoredReason) }}</div>
        </ng-container>
        <div class="text" *ngIf="m.body">{{ m.body }}</div>
        <div class="meta">
          <span *ngIf="m.edited">изменено · </span><span *ngIf="m.deleted">удалено отправителем · </span>
          <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time>
        </div>
      </div>
    </div>
    <!-- Esc ловит сам оверлей (он в фокусе) и дальше не пускает: иначе карточка обращения закрылась бы вместе с фото -->
    <div class="zoom" #zoomEl *ngIf="zoomed && imageUrl" tabindex="-1" (click)="zoomed = false"
         (keydown.escape)="closeZoom($event)" role="dialog" aria-modal="true" aria-label="Фото целиком">
      <img [src]="imageUrl" [alt]="zoomName || 'фото'" />
    </div>
  `,
  styles: [`
    .row { display: flex; margin: 4px 0; }
    .row.out { justify-content: flex-end; }
    .bubble { max-width: min(78%, 560px); background: var(--surface-2); border: 1px solid var(--border); border-radius: 12px; padding: 8px 10px; font-size: 14px; color: var(--text); }
    /* исходящие — подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit, §12 CLAUDE.md) */
    .bubble.out { background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: color-mix(in srgb, var(--success) 35%, var(--border)); }
    .author { font-size: 12px; font-weight: 600; color: var(--accent); margin-bottom: 2px; }
    .subj { font-weight: 600; margin-bottom: 4px; overflow-wrap: anywhere; }
    .text { white-space: pre-line; overflow-wrap: anywhere; }
    /* #000 в mask-image — не цвет, а непрозрачность маски (затухание свёрнутого письма): тема на неё не влияет */
    .text.clamped { max-height: 12em; overflow: hidden; -webkit-mask-image: linear-gradient(180deg, #000 70%, transparent); mask-image: linear-gradient(180deg, #000 70%, transparent); }
    .more { margin: 2px 0 4px; font-size: 12px; }
    .bubble.deleted .text { text-decoration: line-through; color: var(--text-muted); }
    .meta { font-size: 11px; color: var(--text-muted); text-align: right; margin-top: 4px; }
    .thumb { display: block; padding: 0; border: none; background: none; cursor: zoom-in; margin-bottom: 4px; }
    .thumb img { display: block; max-width: 220px; max-height: 220px; border-radius: 8px; }
    .thumb-ph { display: inline-block; font-size: 12px; color: var(--text-muted); padding: 24px 12px; }
    .file { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; font-size: 13px; }
    .file.muted, .muted { color: var(--text-muted); }
    .linklike { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; text-align: left; overflow-wrap: anywhere; }
    .size { color: var(--text-muted); }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    /* вуаль — самое частое значение приложения; пятое не заводить (§16 CLAUDE.md) */
    .zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; cursor: zoom-out; outline: none; }
    .zoom img { max-width: 94vw; max-height: 90vh; border-radius: 8px; }
    /* звонок — служебная строка по центру, не пузырь (спека whatsapp-waha §8) */
    .call { display: flex; justify-content: center; margin: 6px 0; }
    .call-line { font-size: 12px; color: var(--text-muted); background: color-mix(in srgb, var(--text-muted) 15%, transparent);
                 border-radius: 999px; padding: 4px 12px; text-align: center; }
    @media (max-width: 900px) {
      .bubble { max-width: 88%; }
      .thumb img { max-width: 180px; }
    }
  `],
})
export class ChatMessageComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) m!: any;
  @Input({ required: true }) chatId!: number;
  @Input() showAuthor = false;
  @Input() canParse = false;
  @Output() parse = new EventEmitter<any>();

  imageUrl: string | null = null;
  imageFailed = false;
  zoomed = false;
  zoomName = '';
  isLong = false;
  expanded = false;
  private loadedFor: number | null = null;
  private imageSub?: Subscription;

  /** Оверлей появился — забираем фокус, чтобы Esc пришёл к нему, а не к карточке под ним. */
  @ViewChild('zoomEl') set zoomEl(el: ElementRef<HTMLElement> | undefined) { el?.nativeElement.focus(); }

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    this.isLong = this.m?.type === 'EMAIL' && isLongText(this.m.body);
    if (this.m?.type === 'EMAIL') return;          // у письма картинки — по нажатию на файл, без миниатюр
    const a = this.m?.attachment;
    if (a?.image && this.loadedFor !== a.id) {
      this.loadedFor = a.id;
      this.zoomName = a.fileName || '';
      this.loadImage(a.id);
    }
  }

  /** Запрос отменяется вместе с видом: иначе ответ создал бы blob-адрес, который уже некому отозвать. */
  ngOnDestroy() {
    this.imageSub?.unsubscribe();
    this.revoke();
  }

  onThumb() {
    if (this.imageUrl) this.zoomed = true;
    else if (this.imageFailed && this.loadedFor !== null) this.loadImage(this.loadedFor);
  }

  closeZoom(ev: Event) {
    ev.stopPropagation();
    this.zoomed = false;
  }

  /** Файл письма: картинка — крупно в странице, остальное — скачать. */
  openFile(a: any) {
    if (!a.image) { this.download(a); return; }
    this.imageSub?.unsubscribe();
    this.imageSub = this.api.getChatAttachment(this.chatId, a.id).subscribe({
      next: blob => {
        this.revoke();
        this.imageUrl = URL.createObjectURL(blob);
        this.zoomName = a.fileName || '';
        this.zoomed = true;
        this.cdr.detectChanges();
      },
      error: () => this.notify.error(this.fileError()),
    });
  }

  private loadImage(attachmentId: number) {
    this.imageSub?.unsubscribe();
    this.imageFailed = false;
    this.imageSub = this.api.getChatAttachment(this.chatId, attachmentId).subscribe({
      next: blob => { this.revoke(); this.imageUrl = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => { this.imageFailed = true; this.cdr.detectChanges(); },
    });
  }

  download(a: any) {
    this.api.getChatAttachment(this.chatId, a.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = a.fileName || 'file';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
      },
      error: () => this.notify.error(this.fileError()),
    });
  }

  size(bytes: number): string {
    if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1).replace('.', ',') + ' МБ';
    if (bytes >= 1024) return Math.round(bytes / 1024) + ' КБ';
    return bytes + ' Б';
  }

  notStored(reason: string): string {
    if (this.m?.type === 'EMAIL') return reason === 'TOO_LARGE' ? 'больше предела — откройте в почте' : 'не сохранён — откройте в почте';
    return NOT_STORED[reason] || 'не сохранён — смотрите в телефоне';
  }

  full(iso: string) { return fullDateTime(iso); }

  /** Сегодня — «14:05», иначе «28.09 14:05». */
  time(iso: string): string {
    const t = new Date(iso);
    if (isNaN(t.getTime())) return '';
    const hm = t.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
    return t.toDateString() === new Date().toDateString()
      ? hm : t.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }) + ' ' + hm;
  }

  private fileError(): string {
    return this.m?.type === 'EMAIL' ? 'Файл не скачался — откройте его в почте' : 'Файл не скачался — смотрите его в телефоне';
  }

  private revoke() {
    if (this.imageUrl) {
      URL.revokeObjectURL(this.imageUrl);
      this.imageUrl = null;
    }
  }
}
