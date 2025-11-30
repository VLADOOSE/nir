import React from 'react'
import { NavLink } from 'react-router-dom'

const MenuItem = ({ to, children }) => (
    <NavLink to={to} className={({isActive}) => isActive ? 'block px-4 py-2 rounded bg-blue-100 text-blue-700' : 'block px-4 py-2 text-gray-700 hover:bg-gray-100 rounded'}>
        {children}
    </NavLink>
)

export default function SideMenu() {
    return (
        <aside className="w-64 bg-white border-r">
            <div className="p-4 border-b">
                <h2 className="text-lg font-bold">Меню</h2>
            </div>
            <nav className="p-2 space-y-1">
                <div className="text-xs text-gray-500 px-3">Справочники</div>
                <MenuItem to="/med-equipment">Медоборудование</MenuItem>
                <MenuItem to="/facilities">Лечебные учреждения</MenuItem>
                <MenuItem to="/distributors">Дистрибьютеры</MenuItem>

                <div className="text-xs text-gray-500 px-3 mt-3">Учёт деятельности</div>
                <MenuItem to="/activity/apply">Заявка на оборудование</MenuItem>
                <MenuItem to="/activity/tender">Проведение тендера</MenuItem>

                <div className="text-xs text-gray-500 px-3 mt-3">Запросы</div>
                <MenuItem to="/requests">Поиск / Фильтры</MenuItem>

                <div className="text-xs text-gray-500 px-3 mt-3">Отчёты</div>
                <MenuItem to="/reports">Составить заявку / КП</MenuItem>

                <div className="text-xs text-gray-500 px-3 mt-3">Пользователи</div>
                <MenuItem to="/users">Пользователи</MenuItem>

                <div className="text-xs text-gray-500 px-3 mt-3">О системе</div>
                <MenuItem to="/about">О системе</MenuItem>
            </nav>
        </aside>
    )
}
