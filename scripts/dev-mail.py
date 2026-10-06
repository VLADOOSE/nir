#!/usr/bin/env python3
"""Письма в локальный GreenMail (./gradlew devMail) для живой проверки приёма zakup@:
  python3 scripts/dev-mail.py plain            — письмо с вложением (прочее)
  python3 scripts/dev-mail.py reply <prId>     — ответ поставщика с ценой на запрос КП
  python3 scripts/dev-mail.py bounce <prId>    — возврат почтового сервера (DSN, Action: failed) по запросу КП
  python3 scripts/dev-mail.py delayed <prId>   — отложенная доставка (DSN, Action: delayed, 4.4.1) по запросу КП
  python3 scripts/dev-mail.py auto <prId>      — автоответ на запрос КП
"""
import smtplib
import sys
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


def reply(pr_id):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Re: [КП-{pr_id}] Запрос коммерческого предложения"
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


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "plain":
        plain()
    elif cmd in ("reply", "bounce", "delayed", "auto") and len(sys.argv) > 2:
        {"reply": reply, "bounce": bounce, "delayed": delayed, "auto": auto}[cmd](sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)
