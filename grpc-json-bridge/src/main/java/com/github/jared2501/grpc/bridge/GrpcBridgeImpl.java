/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GrpcBridgeImpl implements GrpcBridge {

    private static final Logger log = LoggerFactory.getLogger(GrpcBridgeImpl.class);

    private final Map<String, Channel> serviceChannels = Maps.newConcurrentMap();
    private final Map<String, ServiceIndex> serviceIndices = Maps.newConcurrentMap();

    @Override
    public InvocationHandle invoke(
            String serverName, String fullMethodName, String jsonInput, InvocationObserver observer) {
        return new InvocationHandle() {

            private final Object lock = new Object();
            private boolean cancelled = false;
            private ClientCall<byte[], byte[]> call;

            @Override
            public void start() {
                Channel channel = serviceChannels.get(serverName);
                ServiceIndex serviceIndex = serviceIndices.get(serverName);

                if (channel == null) {
                    observer.onError(InvocationErrorType.SERVICE_NOT_FOUND, null);
                    return;
                } else if (serviceIndex == null) {
                    observer.onError(InvocationErrorType.SERVICE_UNAVAILABLE, null);
                    return;
                }

                Optional<AvailableMethod> maybeMethod = serviceIndex.getMethod(fullMethodName);
                if (!maybeMethod.isPresent()) {
                    observer.onError(InvocationErrorType.METHOD_NOT_FOUND, null);
                    return;
                }
                AvailableMethod method = maybeMethod.get();

                // Generate the request
                DynamicMessage.Builder request = DynamicMessage.newBuilder(method.methodDescriptor().getInputType());
                JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(method.typeRegistry());
                try {
                    parser.merge(jsonInput, request);
                } catch (InvalidProtocolBufferException e) {
                    observer.onError(
                            InvocationErrorType.UNKNOWN,
                            new RuntimeException("Exception encountered when converting JSON to proto", e));
                    return;
                }

                // Start the call
                synchronized (lock) {
                    if (cancelled) {
                        return;
                    }
                    call = channel.newCall(getMethodDescriptor(fullMethodName), CallOptions.DEFAULT);
                    call.start(
                            new ClientCall.Listener<byte[]>() {

                                private byte[] firstMessage;

                                @Override
                                public void onHeaders(Metadata headers) {}

                                @Override
                                public void onMessage(byte[] messageBytes) {
                                    if (firstMessage == null) {
                                        firstMessage = messageBytes;
                                    } else {
                                        observer.onError(InvocationErrorType.NON_UNARY_RESPONSE, null);
                                        call.halfClose();
                                        call.cancel("Expected unary response", new RuntimeException());
                                    }
                                }

                                @Override
                                public void onClose(Status status, Metadata trailers) {
                                    if (status == Status.OK) {
                                        deliverMessage();
                                    } else {
                                        // TODO: deliver error
                                    }
                                }

                                @Override
                                public void onReady() {}

                                private void deliverMessage() {
                                    DynamicMessage message;
                                    try {
                                        message = DynamicMessage.parseFrom(
                                                method.methodDescriptor().getOutputType(), firstMessage);
                                    } catch (InvalidProtocolBufferException e) {
                                        observer.onError(
                                                InvocationErrorType.UNKNOWN,
                                                new RuntimeException("Exception encountered when parsing message", e));
                                        return;
                                    }

                                    StringBuilder jsonOutput = new StringBuilder();
                                    try {
                                        JsonFormat.printer()
                                                .usingTypeRegistry(method.typeRegistry())
                                                .appendTo(message, jsonOutput);
                                    } catch (IOException e) {
                                        observer.onError(
                                                InvocationErrorType.UNKNOWN,
                                                new RuntimeException(
                                                        "Exception encountered when printing response", e));
                                        return;
                                    }

                                    observer.onResult(jsonOutput.toString());
                                }
                            },
                            new Metadata());
                    call.sendMessage(request.build().toByteArray());
                    call.halfClose();
                    call.request(2); // Request two messages to detect if method is unary or not
                }
            }

            @Override
            public void cancel() {
                synchronized (lock) {
                    cancelled = true;
                    if (call != null) {
                        call.halfClose();
                        call.cancel("Cancellation requested", new RuntimeException());
                        call = null;
                    }
                }
            }
        };
    }

    private void updateServices(Map<String, Channel> newServices) {
        Iterables.removeIf(serviceChannels.keySet(), serverName -> !newServices.containsKey(serverName));
        Iterables.removeIf(serviceIndices.keySet(), serverName -> !newServices.containsKey(serverName));

        serviceChannels.putAll(newServices);
        for (Map.Entry<String, Channel> newServer : newServices.entrySet()) {
            if (!serviceIndices.containsKey(newServer.getKey())) {
                //                ServiceIndex index = serviceIndexFactory.create();
                //                serviceIndices.put(newServer.getKey(), index);
                //                updateService(newServer.getKey(), newServer.getValue(), index);
            }
        }
    }

    private void updateService(String serverName, Channel channel, ServiceIndex index) {
        // TODO handle failure case
    }

    private static MethodDescriptor<byte[], byte[]> getMethodDescriptor(String fullMethodName) {
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
