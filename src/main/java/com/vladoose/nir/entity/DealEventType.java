package com.vladoose.nir.entity;

/**
 * Событие сделки (V32): заметка и звонок оператора; смена этапа — пишется там, где действие уже происходит; письмо клиенту
 * из сделки (EMAIL, ref — переписка; спека 2026-10-19-client-mail-reply §3.3) — лента показывает его переписку.
 */
public enum DealEventType { NOTE, CALL, STAGE, EMAIL }
