import React from 'react'
export default function Header() {
    return (
        <header className="flex items-center justify-between p-4 border-b bg-white">
            <div className="flex items-center gap-3">
                <div className="text-2xl font-bold text-blue-700">MedTech</div>
                <div className="text-sm text-gray-500">Учёт и справочники</div>
            </div>
            <div className="flex items-center gap-3">
                <div className="text-sm text-gray-600">v1.0.0</div>
                {/* при необходимости сюда добавим профиль пользователя */}
            </div>
        </header>
    )
}
