import { Routes } from '@angular/router';
import { LayoutComponent } from './layout/layout.component';
import { TendersComponent } from './pages/tenders/tenders.component';
import { EquipmentComponent } from './pages/equipment/equipment.component';
import { FacilitiesComponent } from './pages/facilities/facilities.component';
import { DistributorsComponent } from './pages/distributors/distributors.component';
import { AppliesComponent } from './pages/applies/applies.component';
import { UsersComponent } from './pages/users/users.component';

export const routes: Routes = [
  {
    path: '',
    component: LayoutComponent,
    children: [
      { path: '', redirectTo: 'tenders', pathMatch: 'full' },
      { path: 'tenders', component: TendersComponent },
      { path: 'equipment', component: EquipmentComponent },
      { path: 'facilities', component: FacilitiesComponent },
      { path: 'distributors', component: DistributorsComponent },
      { path: 'applies', component: AppliesComponent },
      { path: 'users', component: UsersComponent },
    ]
  }
];
