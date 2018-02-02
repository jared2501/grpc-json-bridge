/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.github.jared2501.grpc.bridge.test.TestServiceImpl;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
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

    StreamObserver<ServerReflectionRequest> reqStream;

    @Test
    void barbaz() throws InterruptedException, Descriptors.DescriptorValidationException {
        ServerReflectionGrpc.ServerReflectionStub reflection = ServerReflectionGrpc.newStub(channel);

        // Steps:
        // 1. List all services + kick of requests for their protos
        // 2. For every proto get it's dependencies and if we don't have them then request them and record graph
        // 3. Compile all protos
        // 4. Build a type registry
        // 5. Notify the consumer

        Map<String, DescriptorProtos.FileDescriptorProto> protoDescriptors = Maps.newHashMap();

        reqStream = reflection.serverReflectionInfo(
                new StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse value) {
                        System.out.println("all protos: " + protoDescriptors);

                        for (ByteString protoBytes : value.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                            DescriptorProtos.FileDescriptorProto protoDescriptor;
                            try {
                                protoDescriptor = DescriptorProtos.FileDescriptorProto.parseFrom(protoBytes);
                            } catch (InvalidProtocolBufferException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                            protoDescriptors.put(protoDescriptor.getName(), protoDescriptor);

                            for (String dependencyFileName : protoDescriptor.getDependencyList()) {
                                if (!protoDescriptors.containsKey(dependencyFileName)) {
                                    reqStream.onNext(ServerReflectionRequest.newBuilder()
                                            .setFileByFilename(dependencyFileName)
                                            .build());
                                }
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        t.printStackTrace();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("done!");
                    }
                });
        reqStream.onNext(ServerReflectionRequest.newBuilder()
                .setFileContainingSymbol("com.github.jared2501.grpc.bridge.test.TestService")
                .build());

        Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(null, null);
        JsonFormat.TypeRegistry.newBuilder()
                .add(fileDescriptor.getMessageTypes())
                .build();

        Thread.sleep(5000);
    }

}
