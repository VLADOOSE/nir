import React, { useEffect, useState } from 'react'
import axios from 'axios'

export default function UsersPage(){
    const [users, setUsers] = useState([])
    useEffect(()=>load(),[])
    async function load(){ const r = await axios.get('/api/users'); setUsers(r.data) }
    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Пользователи (только для админов)</h2>
            <table className="w-full bg-white rounded shadow">
                <thead className="bg-blue-50 text-left"><tr><th className="p-2">ID</th><th className="p-2">Логин</th><th className="p-2">Роль</th></tr></thead>
                <tbody>
                {users.map(u => <tr key={u.id}><td className="p-2">{u.id}</td><td className="p-2">{u.username}</td><td className="p-2">{u.role}</td></tr>)}
                </tbody>
            </table>
        </div>
    )
}
