/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import io.grpc.reflection.v1alpha.ServerReflectionGrpc;

public interface ServerReflectionStubProvider {

    ServerReflectionGrpc.ServerReflectionStub get(String serviceName);

}
