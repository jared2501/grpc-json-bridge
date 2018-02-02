/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.util.JsonFormat;
import java.util.concurrent.CompletableFuture;

public interface GrpcReflectionClient {

    interface Provider {
        GrpcReflectionClient get(String serviceName);
    }

    CompletableFuture<JsonFormat.TypeRegistry> getTypeRegistry();

}
