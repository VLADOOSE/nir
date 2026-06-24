import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideAppInitializer, provideZoneChangeDetection, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  provideLucideIcons,
  LucideLayoutDashboard, LucideFileText, LucideStethoscope, LucideClipboardList,
  LucideBuilding2, LucideTruck, LucideChartBar, LucideMail, LucideTrendingUp,
  LucideFileSpreadsheet, LucideSearch, LucidePlus, LucidePencil, LucideTrash2,
  LucideFilter, LucideSettings, LucideCalendar, LucideUsers, LucideX,
  LucideChevronDown, LucideChevronUp, LucideStar, LucideDownload,
  LucideTriangleAlert, LucideCircleCheck, LucideClock, LucideLogOut, LucideUser,
  LucideHandshake, LucideFileBox, LucideHistory, LucideRefreshCw, LucideEye,
  LucideExternalLink, LucideBadgeCheck
} from '@lucide/angular';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';
import { marketInterceptor } from './interceptors/market.interceptor';
import { AuthService } from './services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor, marketInterceptor])),
    provideLucideIcons(
      LucideLayoutDashboard, LucideFileText, LucideStethoscope, LucideClipboardList,
      LucideBuilding2, LucideTruck, LucideChartBar, LucideMail, LucideTrendingUp,
      LucideFileSpreadsheet, LucideSearch, LucidePlus, LucidePencil, LucideTrash2,
      LucideFilter, LucideSettings, LucideCalendar, LucideUsers, LucideX,
      LucideChevronDown, LucideChevronUp, LucideStar, LucideDownload,
      LucideTriangleAlert, LucideCircleCheck, LucideClock, LucideLogOut, LucideUser,
      LucideHandshake, LucideFileBox, LucideHistory, LucideRefreshCw, LucideEye,
      LucideExternalLink, LucideBadgeCheck
    ),
    provideAppInitializer(() => {
      const auth = inject(AuthService);
      return firstValueFrom(auth.loadCurrentUser());
    })
  ]
};
