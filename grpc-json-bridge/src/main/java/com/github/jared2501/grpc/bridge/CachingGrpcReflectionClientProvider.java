/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.util.JsonFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;

public class CachingGrpcReflectionClientProvider implements GrpcReflectionClient.Provider {

    private final ServerReflectionStubProvider stubProvider;
    private final AsyncLoadingCache<String, JsonFormat.TypeRegistry> typeRegistries = Caffeine.newBuilder()
            .buildAsync(new AsyncCacheLoader<String, JsonFormat.TypeRegistry>() {
                @Nonnull
                @Override
                public CompletableFuture<JsonFormat.TypeRegistry> asyncLoad(String serviceName, Executor executor) {
                    stubProvider.get(serviceName)
                    return null;
                }
            });

    public CachingGrpcReflectionClientProvider(ServerReflectionStubProvider stubProvider) {
        this.stubProvider = stubProvider;
    }

    @Override
    public GrpcReflectionClient get(String serviceName) {
        return () -> typeRegistries.get(serviceName);
    }
}
