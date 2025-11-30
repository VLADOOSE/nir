import React, { useState } from 'react'
import axios from 'axios'

export default function ActivityApplyPage(){
    const [form, setForm] = useState({ tenderId:'', facilityId:'', items:[{medEquipId:'', qty:1}] })

    function addItem(){ setForm({...form, items:[...form.items, {medEquipId:'', qty:1}]}) }
    function updateItem(idx, key, value){ const items=[...form.items]; items[idx][key]=value; setForm({...form, items}) }

    async function submit(){
        await axios.post('/api/activity/apply', form)
        alert('Заявка отправлена')
    }

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Заявка на оборудование</h2>
            <div className="mb-3 grid grid-cols-3 gap-3">
                <input placeholder="№ Тендера" value={form.tenderId} onChange={e=>setForm({...form,tenderId:e.target.value})} />
                <input placeholder="ID учреждения" value={form.facilityId} onChange={e=>setForm({...form,facilityId:e.target.value})} />
            </div>

            <div className="mb-3">
                <h4 className="font-semibold">Позиции</h4>
                {form.items.map((it, idx)=>(
                    <div key={idx} className="flex gap-2 mb-2">
                        <input placeholder="ID оборудования" value={it.medEquipId} onChange={e=>updateItem(idx,'medEquipId',e.target.value)} />
                        <input placeholder="Кол-во" type="number" value={it.qty} onChange={e=>updateItem(idx,'qty',e.target.value)} className="w-28" />
                    </div>
                ))}
                <button onClick={addItem} className="text-blue-600 underline">Добавить позицию</button>
            </div>

            <button onClick={submit} className="bg-green-600 text-white px-4 py-1 rounded">Отправить заявку</button>
        </div>
    )
}
