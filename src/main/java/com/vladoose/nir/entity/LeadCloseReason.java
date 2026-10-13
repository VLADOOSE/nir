package com.vladoose.nir.entity;

/** NOT_CLIENT — «Не клиент» из почты: ставится только кнопкой, она же отмечает переписку (спека info-mail-leads §2.5). */
public enum LeadCloseReason { ANSWERED, SPAM, DUPLICATE, NOT_OUR_PROFILE, CLIENT_DECLINED, OTHER, NOT_CLIENT }
