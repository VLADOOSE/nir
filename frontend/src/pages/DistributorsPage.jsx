import React, { useEffect, useState } from 'react'
import axios from 'axios'

export default function DistributorsPage(){
    const [list, setList] = useState([])
    const [form, setForm] = useState({ id:null, name:'', inn:'', contact:'' })

    useEffect(()=>load(),[])

    async function load(){ const r = await axios.get('/api/distributors'); setList(r.data) }
    async function save(){
        if(!form.name) return alert('Введите название')
        if(form.id) await axios.put(`/api/distributors/${form.id}`, form)
        else await axios.post('/api/distributors', form)
        setForm({ id:null, name:'', inn:'', contact:'' })
        load()
    }
    function edit(i){ setForm(i) }
    async function remove(id){ if(confirm('Удалить?')){ await axios.delete(`/api/distributors/${id}`); load() } }

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Дистрибьютеры</h2>
            <div className="mb-4 flex gap-2">
                <input value={form.name} onChange={e=>setForm({...form,name:e.target.value})} placeholder="Название" className="flex-1" />
                <input value={form.inn} onChange={e=>setForm({...form,inn:e.target.value})} placeholder="ИНН" className="w-44" />
                <input value={form.contact} onChange={e=>setForm({...form,contact:e.target.value})} placeholder="Контакты" className="w-56" />
                <button onClick={save} className="bg-blue-600 text-white px-4 rounded">{form.id ? 'Обновить' : 'Добавить'}</button>
            </div>

            <table className="w-full bg-white rounded shadow">
                <thead className="bg-blue-50 text-left">
                <tr><th className="p-2">ID</th><th className="p-2">Название</th><th className="p-2">ИНН</th><th className="p-2">Контакты</th><th className="p-2">Действия</th></tr>
                </thead>
                <tbody>
                {list.map(d => (
                    <tr className="border-t hover:bg-gray-50" key={d.id}>
                        <td className="p-2">{d.id}</td>
                        <td className="p-2">{d.name}</td>
                        <td className="p-2">{d.inn}</td>
                        <td className="p-2">{d.contact}</td>
                        <td className="p-2">
                            <button onClick={()=>edit(d)} className="text-blue-600 mr-3">Изменить</button>
                            <button onClick={()=>remove(d.id)} className="text-red-600">Удалить</button>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    )
}
