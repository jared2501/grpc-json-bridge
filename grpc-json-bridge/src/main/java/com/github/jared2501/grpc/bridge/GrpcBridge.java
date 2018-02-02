/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

public interface GrpcBridge {
    interface InvocationHandle {
        void start();
        void cancel();
    }

    interface InvocationObserver {
        void onResult(String jsonOutput);
        void onMethodNotFound();
        void onError(Throwable error);
    }

    /**
     * Invokes the specified method on the specified service with the given JSON input, returns the JSON output of the
     * result. Note that streaming methods will result in an error state for the returned future.
     */
    InvocationHandle invoke(String serviceName, String fullMethodName, String jsonInput, InvocationObserver observer);
}
