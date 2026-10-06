package com.vladoose.nir.integration.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Байты тела с обрывом на пределе — внутри обмена, чтобы дедлайн (GatewayHttp, UpstreamHttp) покрывал и чтение тела. */
public final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {

    private final long maxBytes;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    private long total;

    public LimitedBytes(long maxBytes) { this.maxBytes = maxBytes; }

    @Override
    public CompletionStage<byte[]> getBody() { return result; }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) return;
        for (ByteBuffer b : items) {
            total += b.remaining();
            if (total > maxBytes) {
                // сперва исход, потом отмена: onError от отмены не должен успеть превратить «больше предела»
                // в «не скачался» (тогда были бы три лишних скачивания и DOWNLOAD_FAILED вместо TOO_LARGE)
                result.completeExceptionally(new FileTooLargeException(maxBytes));
                subscription.cancel();
                return;
            }
            byte[] chunk = new byte[b.remaining()];
            b.get(chunk);
            out.write(chunk, 0, chunk.length);
        }
    }

    @Override
    public void onError(Throwable t) { result.completeExceptionally(t); }

    @Override
    public void onComplete() { result.complete(out.toByteArray()); }
}
