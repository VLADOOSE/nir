import { Routes } from '@angular/router';
import { LoginComponent } from './pages/login/login.component';
import { LayoutComponent } from './layout/layout.component';
import { authGuard } from './guards/auth.guard';
import { adminGuard } from './guards/admin.guard';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { TendersComponent } from './pages/tenders/tenders.component';
import { TenderSearchComponent } from './pages/tender-search/tender-search.component';
import { EquipmentComponent } from './pages/equipment/equipment.component';
import { FacilitiesComponent } from './pages/facilities/facilities.component';
import { DistributorsComponent } from './pages/distributors/distributors.component';
import { AppliesComponent } from './pages/applies/applies.component';
import { ReportsComponent } from './pages/reports/reports.component';
import { UsersComponent } from './pages/users/users.component';
import { AboutComponent } from './pages/about/about.component';
import { EquipmentTypesComponent } from './pages/equipment-types/equipment-types.component';
import { RegistryReconciliationComponent } from './pages/registry-reconciliation/registry-reconciliation.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  {
    path: '',
    component: LayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', component: DashboardComponent },
      { path: 'tenders/search', component: TenderSearchComponent },
      { path: 'tenders', component: TendersComponent },
      { path: 'equipment', component: EquipmentComponent },
      { path: 'registry-reconciliation', component: RegistryReconciliationComponent },
      { path: 'facilities', component: FacilitiesComponent },
      { path: 'distributors', component: DistributorsComponent },
      { path: 'applies', component: AppliesComponent },
      { path: 'reports', component: ReportsComponent },
      { path: 'users', component: UsersComponent, canActivate: [adminGuard] },
      { path: 'equipment-types', component: EquipmentTypesComponent, canActivate: [adminGuard] },
      { path: 'about', component: AboutComponent },
    ]
  }
];
