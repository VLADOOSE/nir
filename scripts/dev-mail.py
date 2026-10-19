#!/usr/bin/env python3
"""Письма в локальный GreenMail (./gradlew devMail) для живой проверки приёма zakup@ и info@:
  python3 scripts/dev-mail.py plain            — письмо с вложением (прочее)
  python3 scripts/dev-mail.py reply <prId>     — ответ поставщика с ценой на запрос КП
  python3 scripts/dev-mail.py reply <prId> <файл> — ответ поставщика с КП во вложении (xlsx, xls, docx, doc, pdf, jpg, png)
  python3 scripts/dev-mail.py bounce <prId>    — возврат почтового сервера (DSN, Action: failed) по запросу КП
  python3 scripts/dev-mail.py delayed <prId>   — отложенная доставка (DSN, Action: delayed, 4.4.1) по запросу КП
  python3 scripts/dev-mail.py auto <prId>      — автоответ на запрос КП
  python3 scripts/dev-mail.py client <адрес>        — письмо клиента на info@: текст, Excel и PDF, картинка подписи
  python3 scripts/dev-mail.py client-plain <адрес>  — продолжение переписки клиента (с цитатой)
  python3 scripts/dev-mail.py answer <адрес>        — наш ответ клиенту «из веб-почты»: в «Отправленные» info@ (IMAP APPEND)
  python3 scripts/dev-mail.py kp <адрес> <файл>     — наш ответ клиенту с КП во вложении: в «Отправленные» info@ (IMAP APPEND)
  python3 scripts/dev-mail.py newsletter|service|site|colleague — рассылка / служебное / уведомление сайта / свой домен на info@
"""
import base64
import imaplib
import os
import smtplib
import subprocess
import sys
import tempfile
from email.message import EmailMessage, Message
from email.mime.base import MIMEBase
from email.mime.message import MIMEMessage
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formatdate, make_msgid

TO = "zakup@westmed.kz"


def send(msg):
    # как у настоящих писем: без Message-ID приём не проверяет повторы (дедуп по Message-ID), без Date — дату отправки
    msg["Message-ID"] = make_msgid(domain="dev-mail.local")
    msg["Date"] = formatdate(localtime=True)
    with smtplib.SMTP("127.0.0.1", 3025) as s:
        s.send_message(msg)
    print("отправлено:", msg["Subject"])


def plain():
    m = EmailMessage()
    m["From"] = "Иван Петров <ivan@medtech.kz>"
    m["To"] = TO
    m["Subject"] = "Прайс-лист на октябрь"
    m.set_content("Добрый день!\nВысылаем актуальный прайс.\n\nС уважением, Иван")
    m.add_attachment(b"%PDF-1.4 stub", maintype="application", subtype="pdf", filename="Прайс октябрь.pdf")
    send(m)


def reply(pr_id, path=None):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Re: [КП-{pr_id}] Запрос коммерческого предложения"
    if path:
        m.set_content("Добрый день!\nКП во вложении.\n\n> Здравствуйте! Просим КП")
        name = os.path.basename(path)
        ext = name.lower().rsplit(".", 1)[-1]
        types = {"xlsx": ("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                 "xls": ("application", "vnd.ms-excel"),
                 "docx": ("application", "vnd.openxmlformats-officedocument.wordprocessingml.document"),
                 "doc": ("application", "msword"), "pdf": ("application", "pdf"),
                 "jpg": ("image", "jpeg"), "jpeg": ("image", "jpeg"), "png": ("image", "png")}
        maintype, subtype = types.get(ext, ("application", "octet-stream"))
        with open(path, "rb") as f:
            m.add_attachment(f.read(), maintype=maintype, subtype=subtype, filename=name)
    else:
        m.set_content("Добрый день!\nЦена 3 450 000 тг, срок поставки 30 дней.\n\n> Здравствуйте! Просим КП")
    send(m)


def dsn(pr_id, subject, text, action, status_code, diagnostic):
    """Отчёт о доставке (RFC 3464) по письму с запросом КП: вид письма решает поле Action."""
    report = MIMEMultipart("report", report_type="delivery-status")
    report["From"] = "Mail Delivery System <MAILER-DAEMON@corp.mail.ru>"
    report["To"] = TO
    report["Subject"] = subject
    report.attach(MIMEText(text, "plain", "utf-8"))
    # тело message/delivery-status — блоки полей (RFC 3464): сборщик писем Python принимает их только списком
    # Message, строку он не пишет (AttributeError: 'str' object has no attribute 'policy')
    per_message = Message()
    per_message["Reporting-MTA"] = "dns; mx.mail.ru"
    per_recipient = Message()
    per_recipient["Final-Recipient"] = "rfc822; nobody@medtech.kz"
    per_recipient["Action"] = action
    per_recipient["Status"] = status_code
    per_recipient["Diagnostic-Code"] = diagnostic
    status = MIMEBase("message", "delivery-status")
    status.set_payload([per_message, per_recipient])
    report.attach(status)
    original = EmailMessage()
    original["From"] = TO
    original["To"] = "nobody@medtech.kz"
    original["Subject"] = f"[КП-{pr_id}] Запрос коммерческого предложения"
    original.set_content("Здравствуйте! Просим КП.")
    report.attach(MIMEMessage(original))
    send(report)


def bounce(pr_id):
    dsn(pr_id, "Undelivered Mail Returned to Sender", "Your message could not be delivered.",
        "failed", "5.1.1", "smtp; 550 5.1.1 User unknown")


def delayed(pr_id):
    # сервер получателя ещё повторяет попытки: приём — вид «Задерживается», запрос КП не трогается, уведомление тихое
    dsn(pr_id, "Delayed Mail (still being retried)",
        "Your message has not been delivered yet. The mail system will continue trying to deliver it.",
        "delayed", "4.4.1", "smtp; 451 4.4.1 Temporary local problem, try again later")


def auto(pr_id):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Автоматический ответ: Re: [КП-{pr_id}] Запрос коммерческого предложения"
    m["Auto-Submitted"] = "auto-replied"
    m.set_content("Я в отпуске до 12.10. По срочным вопросам — +7 700 000 00 00.")
    send(m)


INFO = "info@westmed.kz"
# 1×1 PNG — картинка подписи (inline + Content-ID); контрольные суммы чанков сверены
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def xlsx_bytes():
    path = os.path.join(tempfile.mkdtemp(), "smeta.xlsx")
    subprocess.run(["node", os.path.join(os.path.dirname(__file__), "greenapi-stub.mjs"), "--write-xlsx", path], check=True)
    with open(path, "rb") as f:
        return f.read()


def client(address):
    m = EmailMessage()
    m["From"] = f"Айгуль Сапарова <{address}>"
    m["To"] = INFO
    m["Subject"] = "Запрос цены на кушетки"
    m.set_content("Добрый день!\nПросим КП на позиции из сметы во вложении.\n\nС уважением, Айгуль\nГКП «Областная больница»")
    m.add_alternative('<p>Добрый день!<br>Просим КП на позиции из сметы во вложении.</p>'
                      '<p>С уважением, Айгуль</p><img src="cid:logo">', subtype="html")
    # disposition="inline" — как у Outlook, Gmail и Mail.ru: с одним filename Python поставил бы «attachment», и АИС
    # честно показала бы картинку подписи файлом (правило разбора: Content-ID без attachment — подпись)
    m.get_payload()[1].add_related(PNG, maintype="image", subtype="png", cid="<logo>", filename="image001.png",
                                   disposition="inline")
    m.add_attachment(xlsx_bytes(), maintype="application",
                     subtype="vnd.openxmlformats-officedocument.spreadsheetml.sheet", filename="Смета кушетки.xlsx")
    m.add_attachment(b"%PDF-1.4 stub", maintype="application", subtype="pdf", filename="ТЗ.pdf")
    send(m)


def client_plain(address):
    m = EmailMessage()
    m["From"] = f"Айгуль Сапарова <{address}>"
    m["To"] = INFO
    m["Subject"] = "Re: Запрос цены на кушетки"
    m.set_content("Уточните, пожалуйста, срок поставки.\n\n> Добрый день, Айгуль! КП подготовим сегодня.")
    send(m)


def answer(address):
    """Наш ответ клиенту из веб-почты: Mail.ru кладёт копию в «Отправленные» — здесь IMAP APPEND без даты (сервер ставит
    «сейчас»; дата в APPEND у GreenMail читается 12-часовым шаблоном и в 12:xx уехала бы на 12 часов назад)."""
    m = EmailMessage()
    m["From"] = f"West-Med <{INFO}>"
    m["To"] = address
    m["Cc"] = "manager@westmed.kz"
    m["Subject"] = "Re: Запрос цены на кушетки"
    m["Message-ID"] = make_msgid(domain="westmed.kz")
    m["Date"] = formatdate(localtime=True)
    m.set_content("Добрый день, Айгуль!\nКП подготовим сегодня.\n\nС уважением, West-Med\n\n> Добрый день! Просим КП…")
    imap = imaplib.IMAP4("127.0.0.1", 3143)
    imap.login(INFO, "secret")
    imap.append("Sent", None, None, m.as_bytes())
    imap.logout()
    print("в «Отправленные»:", m["Subject"])


def kp(address, path):
    """Наше письмо клиенту с готовым КП из веб-почты: копия в «Отправленные» info@ (как answer, но с файлом)."""
    m = EmailMessage()
    m["From"] = f"West-Med <{INFO}>"
    m["To"] = address
    m["Subject"] = "Коммерческое предложение West-Med"
    m["Message-ID"] = make_msgid(domain="westmed.kz")
    m["Date"] = formatdate(localtime=True)
    m.set_content("Добрый день!\nВысылаем КП во вложении.\n\nС уважением, West-Med")
    name = os.path.basename(path)
    with open(path, "rb") as f:
        m.add_attachment(f.read(), maintype="application", subtype="octet-stream", filename=name)
    imap = imaplib.IMAP4("127.0.0.1", 3143)
    imap.login(INFO, "secret")
    imap.append("Sent", None, None, m.as_bytes())
    imap.logout()
    print("в «Отправленные»:", m["Subject"], "—", name)


def info_letter(sender, subject, text, **headers):
    m = EmailMessage()
    m["From"] = sender
    m["To"] = INFO
    m["Subject"] = subject
    for k, v in headers.items():
        m[k.replace("_", "-")] = v
    m.set_content(text)
    send(m)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "plain":
        plain()
    elif cmd == "reply" and len(sys.argv) > 2:
        reply(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif cmd in ("bounce", "delayed", "auto") and len(sys.argv) > 2:
        {"bounce": bounce, "delayed": delayed, "auto": auto}[cmd](sys.argv[2])
    elif cmd in ("client", "client-plain", "answer") and len(sys.argv) > 2:
        {"client": client, "client-plain": client_plain, "answer": answer}[cmd](sys.argv[2])
    elif cmd == "kp" and len(sys.argv) > 3:
        kp(sys.argv[2], sys.argv[3])
    elif cmd == "newsletter":
        info_letter("Медтехника <news@medshop.kz>", "Скидки недели", "Скидки до 30%", List_Unsubscribe="<mailto:unsubscribe@medshop.kz>")
    elif cmd == "service":
        info_letter("Mail.ru <security@id.mail.ru>", "Вход в почту с нового устройства", "Выполнен вход в ящик")
    elif cmd == "site":
        info_letter(f"westmed.kz <{INFO}>", "Новая заявка с сайта — westmed.kz", "Заявка № 77")
    elif cmd == "colleague":
        info_letter("Бухгалтерия <buh@westmed.kz>", "Акт сверки", "Во вложении акт")
    else:
        print(__doc__)
        sys.exit(1)
