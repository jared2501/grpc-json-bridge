/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.io.ByteStreams;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

final class GrpcBridgeImpl implements GrpcBridge {

    private final ChannelProvider channelProvider;

    public GrpcBridgeImpl(ChannelProvider channelProvider) {
        this.channelProvider = channelProvider;
    }

    @Override
    public InvocationHandle invoke(
            String serviceName, String fullMethodName, String jsonInput, InvocationObserver observer) {
        return new InvocationHandle() {
            @Override
            public void start() {
                Channel channel = channelProvider.get(serviceName);
                ReflectionResponseObserver reflection = new ReflectionResponseObserver(serviceName);
                reflection.onDoneHandler(() -> {
                    Optional<Descriptors.MethodDescriptor> maybeMethod = reflection.getAvailableMethod(fullMethodName);
                    if (!maybeMethod.isPresent()) {
                        observer.onMethodNotFound();
                        return;
                    }
                    Descriptors.MethodDescriptor method = maybeMethod.get();

                    ClientCall<byte[], byte[]> call = channelProvider.get(serviceName)
                            .newCall(getMethodDescriptor(fullMethodName), CallOptions.DEFAULT);
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
                                        message = DynamicMessage.parseFrom(method.getOutputType(), messageBytes);
                                    } catch (InvalidProtocolBufferException e) {
                                        observer.onError(new RuntimeException(
                                                "InvalidProtocolBufferException encountered when parsing message", e));
                                        return;
                                    }

                                    try {
                                        JsonFormat.printer()
                                                .usingTypeRegistry(reflection.getTypeRegistry())
                                                .appendTo(message, System.out);
                                    } catch (IOException e) {
                                        observer.onError(new RuntimeException(
                                                "IOException encountered when printing response", e));
                                    }
                                }

                                @Override
                                public void onClose(Status status, Metadata trailers) {
                                    System.out.println("closed! " + status);
                                    reflection.cancel();
                                }

                                @Override
                                public void onReady() {
                                }
                            },
                            new Metadata());

                    DynamicMessage.Builder message = DynamicMessage.newBuilder(method.getInputType());
                    JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(reflection.getTypeRegistry());
                    try {
                        parser.merge(jsonInput, message);
                    } catch (InvalidProtocolBufferException e) {
                        observer.onError(new RuntimeException(
                                "InvalidProtocolBufferException encountered when converting JSON to proto", e));
                        return;
                    }

                    call.sendMessage(message.build().toByteArray());
                    call.halfClose();
                    call.request(2);
                });
                reflection.start(ServerReflectionGrpc.newStub(channel));
            }

            @Override
            public void cancel() {
                // TODO(jnewman): implement!
            }
        };
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
