/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import java.util.Optional;

public interface ServiceIndex {

    interface Provider {
        ServiceIndex create();
    }

    Optional<AvailableMethod> getMethod(String fullMethodName);

}
