/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.github.jared2501.grpc.bridge.test.TestServiceImpl;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcBridgeImplIntegrationTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = InProcessServerBuilder.forName("test")
                .addService(TestServiceImpl.INSTANCE)
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName("test").build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown();
        channel.awaitTermination(5, TimeUnit.HOURS);
        server.shutdown();
        server.awaitTermination();
    }

    @Test
    void barbaz() throws InterruptedException, ExecutionException {
        ReflectionBasedTypeRegistrySupplier clientProvider = new ReflectionBasedTypeRegistrySupplier(
                serviceName -> channel, 1, TimeUnit.NANOSECONDS);
        JsonFormat.TypeRegistry typeRegistry = clientProvider.getTypeRegistry("foo").get();
        System.out.println("types: " + typeRegistry.find("com.github.jared2501.grpc.bridge.test.TestMessage"));
    }

    @Test
    void norfquix() throws ExecutionException, InterruptedException {
        ChannelProvider channelProvider = serviceName -> channel;
        GrpcBridgeImpl bridge = new GrpcBridgeImpl(
                channelProvider, new ReflectionBasedTypeRegistrySupplier(channelProvider, 1, TimeUnit.NANOSECONDS));

        CompletableFuture<String> result = bridge.invoke(
                "foo",
                "com.github.jared2501.grpc.bridge.test.TestService/UnaryReqUnaryResp",
                "{\"message\": \"message\", \"empty\": {}}");

        result.get();
    }
}
