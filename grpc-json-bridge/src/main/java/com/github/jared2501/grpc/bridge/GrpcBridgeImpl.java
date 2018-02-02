/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.util.JsonFormat;
import java.util.concurrent.CompletableFuture;

public final class GrpcBridgeImpl implements GrpcBridge {

    private final GrpcReflectionClient.Provider clientProvider;

    public GrpcBridgeImpl(GrpcReflectionClient.Provider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public CompletableFuture<byte[]> invoke(String serviceName, String method, byte[] jsonInput) {
        return clientProvider.get(serviceName)
                .getTypeRegistry()
                .thenApply(typeRegistry -> {
                    JsonFormat.parser().usingTypeRegistry(typeRegistry);

                    return null;
                });
    }

}
