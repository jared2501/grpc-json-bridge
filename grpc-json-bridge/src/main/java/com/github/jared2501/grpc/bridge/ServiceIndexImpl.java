/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.Descriptors;
import java.util.Optional;

public final class ServiceIndexImpl extends AbstractService implements ServiceIndex {

    private final ReflectionChannel reflectionChannel;

    public ServiceIndexImpl() {
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }

    @Override
    public Optional<Descriptors.MethodDescriptor> getMethod(String fullMethodName) {
        return Optional.empty();
    }
}
