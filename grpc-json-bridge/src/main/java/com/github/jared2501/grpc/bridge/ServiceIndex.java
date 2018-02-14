/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.DescriptorProtos;
import java.util.Optional;

public interface ServiceIndex {

    interface Provider {
        ServiceIndex create();
    }

    void restart();

    void addAvailableService(String serviceName);

    void addProto(String fileName, DescriptorProtos.FileDescriptorProto proto);

    void complete();

    boolean isAvailable();

    boolean containsProto(String fileName);

    Optional<AvailableMethod> getMethod(String fullMethodName);

}
