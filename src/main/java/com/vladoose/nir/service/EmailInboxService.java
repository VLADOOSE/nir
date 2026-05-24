package com.vladoose.nir.service;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EmailInboxService {

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    public List<Map<String, String>> getInbox(int count) {
        List<Map<String, String>> emails = new ArrayList<>();
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host.replace("smtp.", "imap."));
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");

        try {
            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect(host.replace("smtp.", "imap."), username, password);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int total = inbox.getMessageCount();
            int start = Math.max(1, total - count + 1);
            Message[] messages = inbox.getMessages(start, total);

            for (int i = messages.length - 1; i >= 0; i--) {
                Message m = messages[i];
                Map<String, String> email = new LinkedHashMap<>();
                email.put("id", String.valueOf(m.getMessageNumber()));
                email.put("from", m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "");
                email.put("subject", m.getSubject() != null ? m.getSubject() : "(без темы)");
                email.put("date", m.getSentDate() != null ? m.getSentDate().toString() : "");
                String text = getTextContent(m);
                email.put("preview", text.substring(0, Math.min(200, text.length())));
                emails.add(email);
            }

            inbox.close(false);
            store.close();
        } catch (Exception e) {
            // Почта не настроена или ошибка подключения
        }
        return emails;
    }

    private String getTextContent(Message m) {
        try {
            if (m.isMimeType("text/plain")) return m.getContent().toString();
            if (m.isMimeType("multipart/*")) {
                MimeMultipart mp = (MimeMultipart) m.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    if (mp.getBodyPart(i).isMimeType("text/plain")) return mp.getBodyPart(i).getContent().toString();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
