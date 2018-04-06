/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.DescriptorProtos;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;

public interface ReflectionChannel {
    interface ReflectionObserver {
        /** Invoked for every available gRPC service. */
        void onAvailableService(String serviceName);

        /** Invoked for every proto file. */
        void onProtoFile(String fileName, DescriptorProtos.FileDescriptorProto proto);

        /**
         * Invoked once all services and proto files have been discovered. No further methods will be invoked on the
         * observer if this method is invoked.
         */
        void onComplete();

        /**
         * Invoked if there are any expected errors.No further methods will be invoked on the observer if this method
         * is invoked.
         */
        void onError(Throwable error);
    }

    interface ReflectionCall {
        /**
         * Cancels the reflection call with the given error. If the call is not complete then the
         * {@link ReflectionObserver} will be notified via {@link ReflectionObserver#onError}.
         */
        void cancel(Throwable reason);
    }

    ReflectionCall startCall(
            String serviceName, ServerReflectionGrpc.ServerReflectionStub reflectionStub, ReflectionObserver observer);
}
