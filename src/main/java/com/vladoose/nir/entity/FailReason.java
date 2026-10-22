package com.vladoose.nir.entity;

/** Почему лот не состоялся (из вывода протокола): никто не подал, одна заявка, приглашённый отказался, другое. */
public enum FailReason { NO_BIDS, ONE_BID, REFUSED, OTHER }
