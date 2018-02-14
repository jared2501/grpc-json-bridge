/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GrpcBridgeImpl implements GrpcBridge {

    private static final Logger log = LoggerFactory.getLogger(GrpcBridgeImpl.class);

    private final ServiceIndex.Provider serviceIndexFactory;
    private final Map<String, Channel> serviceChannels = Maps.newConcurrentMap();
    private final Map<String, ServiceIndex> serviceIndices = Maps.newConcurrentMap();

    GrpcBridgeImpl(ServiceIndex.Provider serviceIndexFactory) {
        this.serviceIndexFactory = serviceIndexFactory;
    }

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
                ServiceIndex index = serviceIndexFactory.create();
                serviceIndices.put(newServer.getKey(), index);
                updateService(newServer.getKey(), newServer.getValue(), index);
            }
        }
    }

    private void updateService(String serverName, Channel channel, ServiceIndex index) {
        // TODO handle failure case
        ReflectionResponseObserver observer = new ReflectionResponseObserver(serverName, index, () -> {
        });
        observer.start(ServerReflectionGrpc.newStub(channel));
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

    private static class ReflectionResponseObserver implements StreamObserver<ServerReflectionResponse> {

        private final String serviceName;
        private final ServiceIndex index;
        private final Runnable onErrorHandler;
        private final Set<ServerReflectionRequest> outstandingRequests = Sets.newHashSet();

        private StreamObserver<ServerReflectionRequest> reqStream;
        private Context.CancellableContext context;

        ReflectionResponseObserver(String serviceName, ServiceIndex index, Runnable onErrorHandler) {
            this.serviceName = serviceName;
            this.index = index;
            this.onErrorHandler = onErrorHandler;
        }

        public void start(ServerReflectionGrpc.ServerReflectionStub serverReflection) {
            context = Context.CancellableContext.current().withCancellation();
            context.run(() -> reqStream = serverReflection.serverReflectionInfo(this));
            reqStream.onNext(ServerReflectionRequest.newBuilder()
                    .setListServices("true")
                    .build());
        }

        public void cancel() {
            context.cancel(new RuntimeException());
        }

        @Override
        public void onNext(ServerReflectionResponse response) {
            if (context.isCancelled()) {
                log.info("Future cancelled, not proceeding...");
                return;
            }

            outstandingRequests.remove(response.getOriginalRequest());

            switch (response.getMessageResponseCase()) {
                case LIST_SERVICES_RESPONSE:
                    requestAllFilesForServices(reqStream, outstandingRequests, response);
                    break;
                case FILE_DESCRIPTOR_RESPONSE:
                    requestUnseenDependencyProtos(
                            reqStream,
                            outstandingRequests,
                            response.getFileDescriptorResponse());
                    break;
                default:
                    log.error("Unexpected response case: {}", response.getMessageResponseCase());
                    break;
            }

            if (outstandingRequests.isEmpty()) {
                index.complete();
            }
        }

        @Override
        public void onError(Throwable error) {
            log.error(
                    "Throwable encountered when streaming handling reflecting for service {}",
                    serviceName, error);
            onErrorHandler.run();
        }

        @Override
        public void onCompleted() {
            log.info("Reflection complete, service {} likely shutting down.", serviceName);
            onErrorHandler.run();
        }

        private void requestAllFilesForServices(
                StreamObserver<ServerReflectionRequest> reqStream,
                Set<ServerReflectionRequest> outstandingRequests,
                ServerReflectionResponse response) {
            for (ServiceResponse service : response.getListServicesResponse().getServiceList()) {
                index.addAvailableService(service.getName());
                makeRequest(
                        reqStream,
                        outstandingRequests,
                        ServerReflectionRequest.newBuilder()
                                .setFileContainingSymbol(service.getName())
                                .build());
            }
        }

        private void requestUnseenDependencyProtos(
                StreamObserver<ServerReflectionRequest> reqStream,
                Set<ServerReflectionRequest> outstandingRequests,
                FileDescriptorResponse response) {
            for (ByteString protoBytes : response.getFileDescriptorProtoList()) {
                DescriptorProtos.FileDescriptorProto protoDescriptor;
                try {
                    protoDescriptor = DescriptorProtos.FileDescriptorProto.parseFrom(protoBytes);
                } catch (InvalidProtocolBufferException e) {
                    log.warn("InvalidProtocolBufferException when parsing proto bytes... skipping", e);
                    continue;
                }

                index.addProto(protoDescriptor.getName(), protoDescriptor);

                for (String dependencyFileName : protoDescriptor.getDependencyList()) {
                    if (!index.containsProto(dependencyFileName)) {
                        makeRequest(
                                reqStream,
                                outstandingRequests,
                                ServerReflectionRequest.newBuilder()
                                        .setFileByFilename(dependencyFileName)
                                        .build());
                    }
                }
            }
        }

        private static void makeRequest(
                StreamObserver<ServerReflectionRequest> reqStream,
                Set<ServerReflectionRequest> outstandingRequests,
                ServerReflectionRequest request) {
            outstandingRequests.add(request);
            reqStream.onNext(request);
        }
    }

}
