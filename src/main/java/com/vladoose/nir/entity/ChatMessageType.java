package com.vladoose.nir.entity;

/** Вид сообщения чата (спека whatsapp-chats §6.5). CALL — служебная строка о входящем звонке (спека whatsapp-waha §8). */
public enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, CALL, OTHER }
