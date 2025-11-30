import React from 'react'
export default function AboutPage(){
    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">О системе</h2>
            <div className="bg-white rounded p-4 shadow">
                <p>Версия: <strong>1.0.0</strong></p>
                <p>Разработчик: Ширяев В. И.</p>
                <p>Описание: Система учёта деятельности медицинской торгующей организации — справочники, учёт, запросы, отчёты.</p>
            </div>
        </div>
    )
}
