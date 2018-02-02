/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import io.grpc.Channel;

public interface ChannelProvider {

    Channel get(String serviceName);

}
