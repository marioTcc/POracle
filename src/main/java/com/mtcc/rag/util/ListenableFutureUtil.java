package com.mtcc.rag.util;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

public class ListenableFutureUtil {

    private ListenableFutureUtil() {}

    public static <T> Mono<T> toMono(final Supplier<ListenableFuture<T>> futureSupplier) {
        return Mono.create(sink -> {
            final ListenableFuture<T> future = futureSupplier.get();

            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(final T result) {
                    sink.success(result);
                }

                @Override
                public void onFailure(final @NonNull Throwable error) {
                    sink.error(error);
                }
            }, MoreExecutors.directExecutor());
            sink.onCancel(() -> future.cancel(true));
        });
    }
}
