import React, { useState } from 'react'
import { jsPDF } from 'jspdf'
import { saveAs } from 'file-saver'
import { Document, Packer, Paragraph, TextRun } from 'docx'

export default function ReportsPage(){
    const [form, setForm] = useState({ title:'Заявка', org:'', text:'' })

    function exportPdf(){
        const doc = new jsPDF()
        doc.setFontSize(14)
        doc.text(form.title, 20, 20)
        doc.setFontSize(11)
        doc.text(`Организация: ${form.org}`, 20, 30)
        doc.text(form.text, 20, 40)
        doc.save(`${form.title}.pdf`)
    }

    async function exportDocx(){
        const doc = new Document({
            sections: [{
                properties: {},
                children: [
                    new Paragraph({ children: [ new TextRun({ text: form.title, bold:true, size:28 }) ] }),
                    new Paragraph({ text: `Организация: ${form.org}` }),
                    new Paragraph({ text: form.text })
                ]
            }]
        })
        const blob = await Packer.toBlob(doc)
        saveAs(blob, `${form.title}.docx`)
    }

    async function sendMail(){
        await fetch('/api/reports/send', {
            method:'POST',
            headers:{'Content-Type':'application/json'},
            body: JSON.stringify(form)
        })
        alert('Отправлено')
    }

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Составить заявку / КП</h2>

            <div className="mb-3">
                <input placeholder="Заголовок" value={form.title} onChange={e=>setForm({...form,title:e.target.value})} className="w-full mb-2" />
                <input placeholder="Организация" value={form.org} onChange={e=>setForm({...form,org:e.target.value})} className="w-full mb-2" />
                <textarea placeholder="Текст" value={form.text} onChange={e=>setForm({...form,text:e.target.value})} className="w-full h-40" />
            </div>

            <div className="flex gap-2">
                <button onClick={exportPdf} className="bg-blue-600 text-white px-4 py-1 rounded">Экспорт в PDF</button>
                <button onClick={exportDocx} className="bg-green-600 text-white px-4 py-1 rounded">Экспорт в Word</button>
                <button onClick={sendMail} className="bg-indigo-600 text-white px-4 py-1 rounded">Отправить на почту</button>
            </div>
        </div>
    )
}
