package com.vladoose.nir.entity;

/** Почему байтов файла нет в АИС (спека whatsapp-chats §6.4): больше предела / файл группы / не скачался. */
public enum AttachmentNotStoredReason { TOO_LARGE, GROUP, DOWNLOAD_FAILED }
