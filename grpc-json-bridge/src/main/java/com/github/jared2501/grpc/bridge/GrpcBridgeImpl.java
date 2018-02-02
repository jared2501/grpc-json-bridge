/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.util.JsonFormat;
import java.util.concurrent.CompletableFuture;

final class GrpcBridgeImpl implements GrpcBridge {

    private final TypeRegistrySupplier typeRegistrySupplier;

    public GrpcBridgeImpl(TypeRegistrySupplier typeRegistrySupplier) {
        this.typeRegistrySupplier = typeRegistrySupplier;
    }

    @Override
    public CompletableFuture<byte[]> invoke(String serviceName, String method, byte[] jsonInput) {
        return typeRegistrySupplier.getTypeRegistry(serviceName)
                .thenApply(typeRegistry -> {
                    JsonFormat.parser().usingTypeRegistry(typeRegistry);
                    return null;
                });
    }

}
