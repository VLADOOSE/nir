package com.vladoose.nir.entity;

/** Статусы устройства калитки (спека device-gate §5). REJECTED, REVOKED, EXPIRED — конечные. */
public enum DeviceStatus { PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED }
