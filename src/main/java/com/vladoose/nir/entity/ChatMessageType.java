package com.vladoose.nir.entity;

/**
 * Вид сообщения чата (спека whatsapp-chats §6.5). CALL — служебная строка о входящем звонке (спека whatsapp-waha §8),
 * EMAIL — письмо почтовой переписки (спека info-mail-leads §2.3).
 */
public enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, CALL, OTHER, EMAIL }
