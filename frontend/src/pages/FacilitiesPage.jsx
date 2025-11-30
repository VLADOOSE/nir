import React, { useEffect, useState } from 'react'
import axios from 'axios'

export default function FacilitiesPage(){
    const [list, setList] = useState([])
    const [form, setForm] = useState({ id: null, name:'', address:'', contact:'' })

    useEffect(()=>load(),[])

    async function load() {
        const r = await axios.get('/api/facilities')
        setList(r.data)
    }

    async function save(){
        if(!form.name) return alert('Введите название')
        if(form.id) await axios.put(`/api/facilities/${form.id}`, form)
        else await axios.post('/api/facilities', form)
        setForm({ id:null, name:'', address:'', contact:''})
        load()
    }

    async function remove(id){
        if(!confirm('Удалить запись?')) return
        await axios.delete(`/api/facilities/${id}`)
        load()
    }

    function edit(item){ setForm(item) }

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Лечебные учреждения</h2>

            <div className="mb-4 flex gap-2">
                <input value={form.name} onChange={e=>setForm({...form,name:e.target.value})} placeholder="Название" className="flex-1" />
                <input value={form.address} onChange={e=>setForm({...form,address:e.target.value})} placeholder="Адрес" className="flex-1" />
                <input value={form.contact} onChange={e=>setForm({...form,contact:e.target.value})} placeholder="Контакты" className="w-56" />
                <button onClick={save} className="bg-blue-600 text-white px-4 rounded">{form.id ? 'Обновить' : 'Добавить'}</button>
            </div>

            <table className="w-full bg-white rounded shadow">
                <thead className="bg-blue-50 text-left">
                <tr><th className="p-2">ID</th><th className="p-2">Название</th><th className="p-2">Адрес</th><th className="p-2">Контакты</th><th className="p-2">Действия</th></tr>
                </thead>
                <tbody>
                {list.map(f => (
                    <tr key={f.id} className="border-t hover:bg-gray-50">
                        <td className="p-2">{f.id}</td>
                        <td className="p-2">{f.name}</td>
                        <td className="p-2">{f.address}</td>
                        <td className="p-2">{f.contact}</td>
                        <td className="p-2">
                            <button onClick={()=>edit(f)} className="text-blue-600 mr-3">Изменить</button>
                            <button onClick={()=>remove(f.id)} className="text-red-600">Удалить</button>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    )
}
