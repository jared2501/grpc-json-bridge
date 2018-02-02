/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.io.ByteStreams;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

final class GrpcBridgeImpl implements GrpcBridge {

    private final ChannelProvider channelProvider;
    private final TypeRegistrySupplier typeRegistrySupplier;

    public GrpcBridgeImpl(ChannelProvider channelProvider, TypeRegistrySupplier typeRegistrySupplier) {
        this.channelProvider = channelProvider;
        this.typeRegistrySupplier = typeRegistrySupplier;
    }

    @Override
    public CompletableFuture<String> invoke(String serviceName, String fullMethodName, String jsonInput) {
        return typeRegistrySupplier.getTypeRegistry(serviceName)
                .thenComposeAsync(typeRegistry -> {
                    ClientCall<byte[], byte[]> call = channelProvider.get(serviceName)
                            .newCall(getMethodDescriptor(fullMethodName), CallOptions.DEFAULT);

                    CompletableFuture<String> future = new CompletableFuture<>();

                    // TODO: handle cancellations? maybe futures not right abstraction?
                    call.start(
                            new ClientCall.Listener<byte[]>() {
                                @Override
                                public void onHeaders(Metadata headers) {
                                    System.out.println("headers: " + headers);
                                }

                                @Override
                                public void onMessage(byte[] messageBytes) {
                                    DynamicMessage message;
                                    try {
                                        message = DynamicMessage.parseFrom(typeRegistry.find(
                                                "com.github.jared2501.grpc.bridge.test.TestMessage"),
                                                messageBytes);
                                    } catch (InvalidProtocolBufferException e) {
                                        e.printStackTrace();
                                        return;
                                    }

                                    try {
                                        JsonFormat.printer().usingTypeRegistry(typeRegistry).appendTo(message, System.out);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        return;
                                    }
                                }

                                @Override
                                public void onClose(Status status, Metadata trailers) {
                                    System.out.println("closed! " + status);
                                }

                                @Override
                                public void onReady() {
                                }
                            },
                            new Metadata());

                    DynamicMessage.Builder message = DynamicMessage.newBuilder(
                            typeRegistry.find("com.github.jared2501.grpc.bridge.test.TestMessage"));
                    JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(typeRegistry);
                    try {
                        parser.merge(jsonInput, message);
                    } catch (InvalidProtocolBufferException e) {
                        future.obtrudeException(new RuntimeException(
                                "InvalidProtocolBufferException encountered when converting JSON to proto", e));
                        return future;
                    }

                    call.sendMessage(message.build().toByteArray());
                    call.halfClose();
                    call.request(2);

                    return future;
                });
    }

    private MethodDescriptor<byte[], byte[]> getMethodDescriptor(String fullMethodName) {
        return MethodDescriptor.newBuilder(ByteMarshaller.INSTANCE, ByteMarshaller.INSTANCE)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .setIdempotent(false)
                .setSafe(false)
                .build();
    }

    private static class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        static final ByteMarshaller INSTANCE = new ByteMarshaller();

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return ByteStreams.toByteArray(stream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
