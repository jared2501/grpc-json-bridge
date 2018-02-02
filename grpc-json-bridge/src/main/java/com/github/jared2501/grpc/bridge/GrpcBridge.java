/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CompletableFuture;

public interface GrpcBridge {
    /**
     * Invokes the specified method on the specified service with the given JSON input, returns the JSON output of the
     * result. Note that streaming methods will result in an error state for the returned future.
     */
    CompletableFuture<byte[]> invoke(String serviceName, String method, byte[] jsonInput);
}
