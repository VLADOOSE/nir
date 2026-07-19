import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly KEY = 'ais.theme';
  private cur: Theme = this.read();
  theme = signal<Theme>(this.cur);

  constructor() { this.apply(this.cur); }

  private read(): Theme {
    const v = localStorage.getItem(this.KEY);
    if (v === 'dark' || v === 'light') return v;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  private apply(t: Theme) { document.documentElement.setAttribute('data-theme', t); }

  get current(): Theme { return this.cur; }
  set(t: Theme) { this.cur = t; localStorage.setItem(this.KEY, t); this.theme.set(t); this.apply(t); }
  toggle() { this.set(this.cur === 'dark' ? 'light' : 'dark'); }
}
