/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReflectionChannelImpl implements ReflectionChannel {

    private static final Logger log = LoggerFactory.getLogger(ReflectionChannelImpl.class);

    @Override
    public ReflectionCall startCall(
            String serviceName, ServerReflectionGrpc.ServerReflectionStub reflectionStub, ReflectionObserver observer) {
        Context.CancellableContext context = Context.CancellableContext.current().withCancellation();
        context.run(() -> {
            ReflectionResponseObserver streamObs = new ReflectionResponseObserver(serviceName, observer);
            StreamObserver<ServerReflectionRequest> reqStream = reflectionStub.serverReflectionInfo(streamObs);
            streamObs.start(reqStream);
        });
        return context::cancel;
    }

    private static class ReflectionResponseObserver implements StreamObserver<ServerReflectionResponse> {

        private final String serviceName;
        private final ReflectionObserver observer;
        private final Set<String> observedProtoFiles = Sets.newHashSet();
        private final Set<ServerReflectionRequest> outstandingRequests = Sets.newHashSet();

        private StreamObserver<ServerReflectionRequest> reqStream;

        ReflectionResponseObserver(String serviceName, ReflectionObserver observer) {
            this.serviceName = serviceName;
            this.observer = observer;
        }

        public void start(StreamObserver<ServerReflectionRequest> requestStreamToRun) {
            reqStream = requestStreamToRun;
            reqStream.onNext(ServerReflectionRequest.newBuilder()
                    .setListServices("true")
                    .build());
        }

        @Override
        public void onNext(ServerReflectionResponse response) {
            outstandingRequests.remove(response.getOriginalRequest());

            switch (response.getMessageResponseCase()) {
                case LIST_SERVICES_RESPONSE:
                    requestAllFilesForServices(response);
                    break;
                case FILE_DESCRIPTOR_RESPONSE:
                    requestUnseenDependencyProtos(response.getFileDescriptorResponse());
                    break;
                default:
                    log.error("Unexpected response case: {}", response.getMessageResponseCase());
                    break;
            }

            if (outstandingRequests.isEmpty()) {
                reqStream.onCompleted();
                observer.onComplete();
            }
        }

        @Override
        public void onError(Throwable error) {
            log.debug("Throwable encountered when streaming handling reflecting for service {}", serviceName, error);
            observer.onError(error);
        }

        @Override
        public void onCompleted() {
            log.debug("Reflection complete, service {} likely shutting down.", serviceName);
            observer.onError(Status.UNAVAILABLE.asException());
        }

        private void requestAllFilesForServices(ServerReflectionResponse response) {
            for (ServiceResponse service : response.getListServicesResponse().getServiceList()) {
                observer.onAvailableService(service.getName());
                makeRequest(ServerReflectionRequest.newBuilder()
                        .setFileContainingSymbol(service.getName())
                        .build());
            }
        }

        private void requestUnseenDependencyProtos(FileDescriptorResponse response) {
            for (ByteString protoBytes : response.getFileDescriptorProtoList()) {
                DescriptorProtos.FileDescriptorProto protoDescriptor;
                try {
                    protoDescriptor = DescriptorProtos.FileDescriptorProto.parseFrom(protoBytes);
                } catch (InvalidProtocolBufferException e) {
                    log.warn("InvalidProtocolBufferException when parsing proto bytes... skipping", e);
                    continue;
                }

                observer.onProtoFile(protoDescriptor.getName(), protoDescriptor);
                observedProtoFiles.add(protoDescriptor.getName());

                for (String dependencyFileName : protoDescriptor.getDependencyList()) {
                    if (!observedProtoFiles.contains(dependencyFileName)) {
                        makeRequest(ServerReflectionRequest.newBuilder()
                                .setFileByFilename(dependencyFileName)
                                .build());
                    }
                }
            }
        }

        private void makeRequest(ServerReflectionRequest request) {
            outstandingRequests.add(request);
            reqStream.onNext(request);
        }
    }
}
