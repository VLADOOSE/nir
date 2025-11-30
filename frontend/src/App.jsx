import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import MedEquipmentPage from './pages/MedEquipmentPage'
import FacilitiesPage from './pages/FacilitiesPage'
import DistributorsPage from './pages/DistributorsPage'
import RequestsPage from './pages/RequestsPage'
import ActivityApplyPage from './pages/ActivityApplyPage'
import ActivityTenderPage from './pages/ActivityTenderPage'
import ReportsPage from './pages/ReportsPage'
import UsersPage from './pages/UsersPage'
import AboutPage from './pages/AboutPage'

export default function App(){
    return (
        <BrowserRouter>
            <Layout>
                <Routes>
                    <Route path="/" element={<Navigate to="/med-equipment" replace />} />
                    <Route path="/med-equipment" element={<MedEquipmentPage />} />
                    <Route path="/facilities" element={<FacilitiesPage />} />
                    <Route path="/distributors" element={<DistributorsPage />} />
                    <Route path="/requests" element={<RequestsPage />} />
                    <Route path="/activity/apply" element={<ActivityApplyPage />} />
                    <Route path="/activity/tender" element={<ActivityTenderPage />} />
                    <Route path="/reports" element={<ReportsPage />} />
                    <Route path="/users" element={<UsersPage />} />
                    <Route path="/about" element={<AboutPage />} />
                </Routes>
            </Layout>
        </BrowserRouter>
    )
}
