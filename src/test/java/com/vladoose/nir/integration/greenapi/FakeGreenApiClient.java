package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Управляемый фейк Green-API: очередь отдаёт ГОЛОВУ, пока её не удалят (как настоящая), файлы по URL. Без сети. */
public class FakeGreenApiClient implements GreenApiClient {

    public boolean configured = true;
    public final Deque<GreenApiReceived> queue = new ArrayDeque<>();
    public final List<Long> deleted = new ArrayList<>();
    public final Map<String, byte[]> files = new HashMap<>();
    /** url → сколько раз ещё упасть на скачивании */
    public final Map<String, Integer> downloadFailuresLeft = new HashMap<>();
    public final List<String> downloads = new ArrayList<>();
    public String state = "authorized";
    public GreenApiSettings settings = new GreenApiSettings(GreenApiJson.WID, "", true, true);
    public RuntimeException failReceiveWith;
    /** Error (не Exception) из receive — как нехватка памяти внутри прохода. */
    public Error failReceiveWithError;
    /** Выполняется в начале receive — например, «зависнуть» на защёлке. */
    public Runnable onReceive;
    public int receiveCalls;
    private long nextReceipt = 1;

    public long enqueue(ObjectNode body) {
        long id = nextReceipt++;
        queue.addLast(new GreenApiReceived(id, body));
        return id;
    }

    @Override
    public boolean isConfigured() { return configured; }

    @Override
    public GreenApiReceived receive(int receiveTimeoutSec) {
        receiveCalls++;
        if (onReceive != null) onReceive.run();
        if (failReceiveWith != null) throw failReceiveWith;
        if (failReceiveWithError != null) throw failReceiveWithError;
        return queue.peekFirst();
    }

    @Override
    public void delete(long receiptId) {
        deleted.add(receiptId);
        queue.removeIf(r -> r.receiptId() == receiptId);
    }

    @Override
    public String state() { return state; }

    @Override
    public GreenApiSettings settings() { return settings; }

    @Override
    public byte[] download(String url, long maxBytes) {
        downloads.add(url);
        int left = downloadFailuresLeft.getOrDefault(url, 0);
        if (left > 0) {
            downloadFailuresLeft.put(url, left - 1);
            throw new GreenApiException(0, "Green-API: файл не скачался: ConnectException");
        }
        byte[] b = files.get(url);
        if (b == null) throw new GreenApiException(404, "Green-API: HTTP 404 при скачивании файла");
        if (b.length > maxBytes) throw new FileTooLargeException(maxBytes);
        return b;
    }
}
