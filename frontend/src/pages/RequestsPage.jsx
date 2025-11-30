import React, { useState } from 'react'
import axios from 'axios'

export default function RequestsPage(){
    const [filters, setFilters] = useState({
        tndrId:'', organization:'', lotCount:'', costFrom:'', costTo:'', stage:'', dateFrom:'', dateTo:'', limit:50,
        lotNo:'', lotMiNo:'', lotName:'', lotDesc:'', lotCountExact:''
    })
    const [results, setResults] = useState([])

    async function search(){
        // POST с фильтрами на backend, пример: /api/search/tenders
        const r = await axios.post('/api/search/tenders', filters)
        setResults(r.data)
    }

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Поиск тендеров / фильтры</h2>

            <div className="grid grid-cols-3 gap-3 mb-4">
                <input placeholder="№ Тендера" value={filters.tndrId} onChange={e=>setFilters({...filters,tndrId:e.target.value})} />
                <input placeholder="Организация" value={filters.organization} onChange={e=>setFilters({...filters,organization:e.target.value})} />
                <input placeholder="Кол-во лотов" value={filters.lotCount} onChange={e=>setFilters({...filters,lotCount:e.target.value})} />
                <input placeholder="Стоимость от" value={filters.costFrom} onChange={e=>setFilters({...filters,costFrom:e.target.value})} />
                <input placeholder="Стоимость до" value={filters.costTo} onChange={e=>setFilters({...filters,costTo:e.target.value})} />
                <input placeholder="Этап тендера" value={filters.stage} onChange={e=>setFilters({...filters,stage:e.target.value})} />
                <input placeholder="Дата начала (YYYY-MM-DD)" value={filters.dateFrom} onChange={e=>setFilters({...filters,dateFrom:e.target.value})} />
                <input placeholder="Дата окончания (YYYY-MM-DD)" value={filters.dateTo} onChange={e=>setFilters({...filters,dateTo:e.target.value})} />
                <input placeholder="Показывать, шт" value={filters.limit} onChange={e=>setFilters({...filters,limit:e.target.value})} />
            </div>

            <h3 className="font-semibold mb-2">Поиск по лоту</h3>
            <div className="grid grid-cols-4 gap-3 mb-4">
                <input placeholder="№ Лота" value={filters.lotNo} onChange={e=>setFilters({...filters,lotNo:e.target.value})} />
                <input placeholder="№ МИ" value={filters.lotMiNo} onChange={e=>setFilters({...filters,lotMiNo:e.target.value})} />
                <input placeholder="Наименование" value={filters.lotName} onChange={e=>setFilters({...filters,lotName:e.target.value})} />
                <input placeholder="Описание" value={filters.lotDesc} onChange={e=>setFilters({...filters,lotDesc:e.target.value})} />
            </div>

            <div className="mb-4">
                <button onClick={search} className="bg-blue-600 text-white px-4 py-1 rounded">Поиск</button>
            </div>

            <div className="bg-white rounded shadow p-3">
                <h4 className="font-semibold mb-2">Результаты ({results.length})</h4>
                <div>
                    {results.map(r =>
                        <div key={r.tndrId} className="p-2 border-b">
                            <div className="font-semibold">Тендер #{r.tndrId} — {r.organization} — {r.cost}</div>
                            <div className="text-sm text-gray-600">Этап: {r.stage} | Лотов: {r.lotsCount}</div>
                        </div>
                    )}
                </div>
            </div>
        </div>
    )
}
