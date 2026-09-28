import { Injectable, NgZone } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Исход церемонии — экран сам решает, что показать (спека passkeys-login §7.1, §8). */
export type PasskeyOutcome = 'ok' | 'cancelled' | 'exists' | 'rejected' | 'session' | 'failed';

/**
 * Вход и добавление ключа по Face ID / Touch ID (WebAuthn). Эндпоинты — встроенные в Spring Security 6.5.5,
 * тела — ровно как у эталонного клиента Spring (spring-security-webauthn.js из spring-security-web), включая
 * credType при входе. Тот же формат шлёт эмулятор ключа в тестах бэкенда (VirtualPasskey).
 * Запросы — через HttpClient: работают общие интерсепторы, в том числе уход на калитку по X-AIS-Gate.
 * Спека: docs/superpowers/specs/2026-09-28-passkeys-login-design.md §6–7.
 *
 * ⚠️ Зона Angular и async/await. zone.js подключён в main.ts, а не в polyfills, поэтому сборщик оставляет
 * async/await нативными, и код после нативного await идёт МИМО зоны: без глобальной перерисовки тосты
 * не появлялись и не гасли (поймано живой проверкой). Отсюда два правила:
 * HTTP-запросы стартуют в зоне (post — побочные эффекты интерсептора, например переход на /login при 401,
 * должны идти в ней), а экраны забирают исход через .then, а не await: .then нативного промиса zone.js
 * патчит, нативный await — нет.
 */
@Injectable({ providedIn: 'root' })
export class PasskeyService {
  constructor(private http: HttpClient, private zone: NgZone) {}

  private post(url: string, body: unknown): Promise<any> {
    return this.zone.run(() => firstValueFrom(this.http.post<any>(url, body)));
  }

  /** Ключ сработает только на своём домене и в браузере с WebAuthn — на Tailscale, туннеле и в старых браузерах нет. */
  usableHere(rpId: string | null | undefined): boolean {
    return !!rpId && typeof window.PublicKeyCredential === 'function' && location.hostname === rpId;
  }

  async login(): Promise<PasskeyOutcome> {
    let options: any;
    try {
      options = await this.post('/webauthn/authenticate/options', null);
    } catch {
      return 'failed';
    }
    let cred: PublicKeyCredential | null;
    try {
      cred = (await navigator.credentials.get({
        publicKey: {
          ...options,
          challenge: fromB64url(options.challenge),
          allowCredentials: (options.allowCredentials || []).map((c: any) => ({ ...c, id: fromB64url(c.id) })),
        },
      })) as PublicKeyCredential | null;
    } catch (e) {
      return ceremonyError(e);
    }
    if (!cred) return 'cancelled';
    const r = cred.response as AuthenticatorAssertionResponse;
    const body = {
      id: cred.id,
      rawId: toB64url(cred.rawId),
      response: {
        authenticatorData: toB64url(r.authenticatorData),
        clientDataJSON: toB64url(r.clientDataJSON),
        signature: toB64url(r.signature),
        userHandle: r.userHandle ? toB64url(r.userHandle) : undefined,
      },
      credType: cred.type,                                    // так шлёт эталонный клиент Spring
      clientExtensionResults: cred.getClientExtensionResults(),
      authenticatorAttachment: cred.authenticatorAttachment,
    };
    try {
      const res = await this.post('/login/webauthn', body);
      return res?.authenticated ? 'ok' : 'rejected';
    } catch (e) {
      return (e as HttpErrorResponse).status === 401 ? 'rejected' : 'failed';
    }
  }

  async register(label: string): Promise<PasskeyOutcome> {
    let options: any;
    try {
      options = await this.post('/webauthn/register/options', null);
    } catch (e) {
      // без входа Spring 6.5.5 отвечает здесь 400, а не 401: фильтр стоит до проверки прав (спека §1.1 п. 7)
      const status = (e as HttpErrorResponse).status;
      return status === 400 || status === 401 ? 'session' : 'failed';
    }
    let cred: PublicKeyCredential | null;
    try {
      cred = (await navigator.credentials.create({
        publicKey: {
          ...options,
          user: { ...options.user, id: fromB64url(options.user.id) },
          challenge: fromB64url(options.challenge),
          excludeCredentials: (options.excludeCredentials || []).map((c: any) => ({ ...c, id: fromB64url(c.id) })),
        },
      })) as PublicKeyCredential | null;
    } catch (e) {
      return ceremonyError(e);
    }
    if (!cred) return 'cancelled';
    const r = cred.response as AuthenticatorAttestationResponse;
    const body = {
      publicKey: {
        credential: {
          id: cred.id,
          rawId: toB64url(cred.rawId),
          response: {
            attestationObject: toB64url(r.attestationObject),
            clientDataJSON: toB64url(r.clientDataJSON),
            transports: typeof r.getTransports === 'function' ? r.getTransports() : [],
          },
          type: cred.type,
          clientExtensionResults: cred.getClientExtensionResults(),
          authenticatorAttachment: cred.authenticatorAttachment,
        },
        label,
      },
    };
    try {
      const res = await this.post('/webauthn/register', body);
      return res?.success ? 'ok' : 'failed';
    } catch (e) {
      return (e as HttpErrorResponse).status === 401 ? 'session' : 'failed';
    }
  }
}

/** Отмену и «ключа на устройстве нет» браузер не различает — оба NotAllowedError (спека §8). */
function ceremonyError(e: unknown): PasskeyOutcome {
  const name = (e as DOMException)?.name;
  if (name === 'InvalidStateError') return 'exists';                                 // ключ этой учётки на устройстве уже есть
  if (name === 'NotAllowedError' || name === 'AbortError') return 'cancelled';
  return 'failed';
}

function toB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function fromB64url(s: string): ArrayBuffer {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));        // atob принимает и без «=» — как у Spring
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}
