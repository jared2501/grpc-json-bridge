/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.github.jared2501.grpc.bridge.test.TestServiceImpl;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
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
    void norfquix() throws ExecutionException, InterruptedException {
        GrpcBridge bridge = new GrpcBridgeImpl(serviceName -> channel);

        GrpcBridge.InvocationHandle handle = bridge.invoke(
                "foo",
                "com.github.jared2501.grpc.bridge.test.TestService/UnaryReqUnaryResp",
                "{\"message\": \"message\", \"empty\": {}}",
                new GrpcBridge.InvocationObserver() {
                    @Override
                    public void onResult(String jsonOutput) {
                        System.out.println("output: " + jsonOutput);
                    }

                    @Override
                    public void onMethodNotFound() {
                        System.out.println("method not found!");
                    }

                    @Override
                    public void onError(Throwable error) {
                        error.printStackTrace();
                    }
                });

        handle.start();

        Thread.sleep(5000);
    }
}
