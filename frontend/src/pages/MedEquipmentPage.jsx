import React, { useEffect, useState } from 'react'
import axios from 'axios'

export default function MedEquipmentPage() {
    const [list, setList] = useState([])
    const [form, setForm] = useState({ medEquipId: null, name: '', spec: '', manufact: '', cost: '' })
    const [loading, setLoading] = useState(false)

    useEffect(() => { load() }, [])

    const load = async () => {
        setLoading(true)
        const r = await axios.get('/api/med-equipment')
        setList(r.data)
        setLoading(false)
    }

    const save = async () => {
        if (!form.name) return alert("Введите название!")
        if (form.medEquipId) {
            // Обновление существующей записи
            await axios.put(`/api/med-equipment/${form.medEquipId}`, form)
        } else {
            // Добавление новой
            await axios.post('/api/med-equipment', form)
        }
        setForm({ medEquipId: null, name: '', spec: '', manufact: '', cost: '' })
        load()
    }

    const edit = (item) => setForm({ ...item }) // берем всю запись, включая medEquipId
    const remove = async (id) => {
        if (confirm('Удалить запись?')) {
            await axios.delete(`/api/med-equipment/${id}`)
            load()
        }
    }

    return (
        <div>
            <h2 className="text-2xl font-semibold text-blue-700 mb-4">Медицинское оборудование</h2>

            <div className="flex gap-2 mb-6">
                <input placeholder="Наименование" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} className="flex-1" />
                <input placeholder="Характеристики" value={form.spec} onChange={e => setForm({ ...form, spec: e.target.value })} className="flex-1" />
                <input placeholder="Производитель" value={form.manufact} onChange={e => setForm({ ...form, manufact: e.target.value })} className="flex-1" />
                <input placeholder="Стоимость" value={form.cost} onChange={e => setForm({ ...form, cost: e.target.value })} className="w-32" />
                <button onClick={save} className="bg-blue-600 text-white px-4 rounded hover:bg-blue-700">
                    {form.medEquipId ? 'Обновить' : 'Добавить'}
                </button>
            </div>

            {loading ? (
                <p className="text-gray-500">Загрузка...</p>
            ) : (
                <table className="w-full border-collapse">
                    <thead>
                    <tr className="bg-blue-100 text-left">
                        <th className="p-2 border">ID</th>
                        <th className="p-2 border">Наименование</th>
                        <th className="p-2 border">Характеристики</th>
                        <th className="p-2 border">Производитель</th>
                        <th className="p-2 border">Стоимость</th>
                        <th className="p-2 border text-center">Действия</th>
                    </tr>
                    </thead>
                    <tbody>
                    {list.map(item => (
                        <tr key={item.medEquipId} className="hover:bg-blue-50">
                            <td className="p-2 border">{item.medEquipId}</td>
                            <td className="p-2 border">{item.name}</td>
                            <td className="p-2 border">{item.spec}</td>
                            <td className="p-2 border">{item.manufact}</td>
                            <td className="p-2 border">{item.cost}</td>
                            <td className="p-2 border text-center">
                                <button onClick={() => edit(item)} className="text-blue-600 hover:underline mr-2">Изменить</button>
                                <button onClick={() => remove(item.medEquipId)} className="text-red-600 hover:underline">Удалить</button>
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            )}
        </div>
    )
}
