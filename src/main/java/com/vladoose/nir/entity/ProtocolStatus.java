package com.vladoose.nir.entity;

/** Протокол итогов лота: разобран, вид незнакомый, не разобран / не скачан, файла ещё нет. */
public enum ProtocolStatus { PARSED, UNKNOWN_FORMAT, FAILED, NO_FILE }
