import React from 'react'
import SideMenu from './SideMenu'
import Header from './Header'

export default function Layout({ children }) {
    return (
        <div className="min-h-screen flex bg-slate-50">
            <SideMenu />
            <div className="flex-1 flex flex-col">
                <Header />
                <main className="p-6">
                    {children}
                </main>
            </div>
        </div>
    )
}
