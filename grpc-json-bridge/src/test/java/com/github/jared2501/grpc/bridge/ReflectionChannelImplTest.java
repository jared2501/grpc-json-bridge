/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jared2501.grpc.bridge.test.TestServiceImpl;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReflectionChannelImplTest {

    private Server server;
    private ManagedChannel channel;
    private ReflectionChannel reflection;

    @BeforeEach
    void setUp() throws IOException {
        server = InProcessServerBuilder.forName("test")
                .addService(TestServiceImpl.INSTANCE)
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName("test").build();
        reflection = new ReflectionChannelImpl();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown();
        channel.awaitTermination(5, TimeUnit.HOURS);
        server.shutdown();
        server.awaitTermination();
    }

    @Test
    void successfulRoundTrip() {
        TestReflectionObserver observer = new TestReflectionObserver();
        reflection.startCall("test", ServerReflectionGrpc.newStub(channel), observer);

        observer.waitUntilComplete();
        assertThat(observer.getError()).isNull();
        assertThat(observer.getAvailableService()).containsExactly(
                "com.github.jared2501.grpc.bridge.test.TestService",
                "grpc.reflection.v1alpha.ServerReflection");
        assertThat(observer.getProtoFiles().keySet()).containsExactly(
                "test.proto",
                "google/protobuf/empty.proto",
                "transitive.proto",
                "io/grpc/reflection/v1alpha/reflection.proto");
    }

    @Test
    void unavailableChannel() throws InterruptedException {
        channel.shutdown();
        channel.awaitTermination(5, TimeUnit.HOURS);
        TestReflectionObserver observer = new TestReflectionObserver();
        reflection.startCall("test", ServerReflectionGrpc.newStub(channel), observer);
        observer.waitUntilComplete();
        assertThat(observer.getError())
                .isInstanceOf(StatusRuntimeException.class)
                .matches(error -> ((StatusRuntimeException) error).getStatus().getCode() == Status.Code.UNAVAILABLE);
    }
}
