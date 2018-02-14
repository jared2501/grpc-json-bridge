/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import javax.annotation.Nullable;

public interface GrpcBridge {
    interface InvocationHandle {
        void start();
        void cancel();
    }

    enum InvocationErrorType {
        SERVICE_NOT_FOUND,
        SERVICE_UNAVAILABLE,
        METHOD_NOT_FOUND,
        NON_UNARY_RESPONSE,
        UNKNOWN
    }

    interface InvocationObserver {
        void onError(InvocationErrorType type, @Nullable Throwable error);
        void onResult(String jsonOutput);
    }

    /**
     * Invokes the specified method on the specified service with the given JSON input, returns the JSON output of the
     * result. Note that streaming methods will result in an error state for the returned future.
     */
    InvocationHandle invoke(String serviceName, String fullMethodName, String jsonInput, InvocationObserver observer);
}
