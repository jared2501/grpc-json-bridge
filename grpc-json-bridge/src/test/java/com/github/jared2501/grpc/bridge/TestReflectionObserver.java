/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Maps;
import com.google.protobuf.DescriptorProtos;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.assertj.core.util.Sets;

public final class TestReflectionObserver implements ReflectionChannel.ReflectionObserver {

    private Set<String> availableService = Sets.newHashSet();
    private Map<String, DescriptorProtos.FileDescriptorProto> protoFiles = Maps.newHashMap();
    private Throwable error;
    private final Semaphore done = new Semaphore(0);

    @Override
    public void onAvailableService(String serviceName) {
        availableService.add(serviceName);
    }

    @Override
    public void onProtoFile(String fileName, DescriptorProtos.FileDescriptorProto proto) {
        protoFiles.put(fileName, proto);
    }

    @Override
    public void onComplete() {
        done.release(1);
    }

    @Override
    public void onError(Throwable err) {
        error = err;
        done.release(1);
    }

    public void waitUntilComplete() {
        done.acquireUninterruptibly(1);
    }

    public Throwable getError() {
        return error;
    }

    public Set<String> getAvailableService() {
        return availableService;
    }

    public Map<String, DescriptorProtos.FileDescriptorProto> getProtoFiles() {
        return protoFiles;
    }
}
