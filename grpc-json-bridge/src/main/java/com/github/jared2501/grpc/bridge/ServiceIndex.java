/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.Descriptors;
import java.util.Optional;

public interface ServiceIndex {

    Optional<Descriptors.MethodDescriptor> getMethod(String fullMethodName);

}
