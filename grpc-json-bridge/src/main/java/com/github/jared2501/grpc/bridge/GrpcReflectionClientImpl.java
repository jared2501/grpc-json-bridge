/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrpcReflectionClientImpl implements GrpcReflectionClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcReflectionClientImpl.class);

    private final String serviceName;
    private final ServerReflectionGrpc.ServerReflectionStub reflection;

    public GrpcReflectionClientImpl(String serviceName, ServerReflectionGrpc.ServerReflectionStub reflection) {
        this.serviceName = serviceName;
        this.reflection = reflection;
    }

    @Override
    public CompletableFuture<JsonFormat.TypeRegistry> getTypeRegistry() {
        CompletableFuture<JsonFormat.TypeRegistry> result = new CompletableFuture<>();

        Set<ServerReflectionRequest> outstandingRequests = Sets.newHashSet();
        Map<String, DescriptorProtos.FileDescriptorProto> protosByFileName = Maps.newHashMap();

        AtomicReference<StreamObserver<ServerReflectionRequest>> reqStream = new AtomicReference<>();
        reqStream.set(reflection.serverReflectionInfo(
                new StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse response) {
                        if (result.isCancelled()) {
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
                                        protosByFileName,
                                        response.getFileDescriptorResponse());
                                break;
                            default:
                                log.error("Unexpected response case: {}", response.getMessageResponseCase());
                                break;
                        }

                        if (outstandingRequests.isEmpty()) {
                            Collection<Descriptors.FileDescriptor> compiledProtos = compileProtos(protosByFileName);
                            JsonFormat.TypeRegistry.Builder typeRegistry = JsonFormat.TypeRegistry.newBuilder();
                            for (Descriptors.FileDescriptor compiledProto : compiledProtos) {
                                typeRegistry.add(compiledProto.getMessageTypes());
                            }
                            result.complete(typeRegistry.build());
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error(
                                "Throwable encountered when streaming handling reflecting for service {}",
                                serviceName, error);
                    }

                    @Override
                    public void onCompleted() {
                        log.info("Reflection complete, service {} likely shutting down.", serviceName);
                    }
                }));

        // List all services to initial a download
        reqStream.get().onNext(ServerReflectionRequest.newBuilder()
                .setListServices("true")
                .build());

        return result;
    }

    private void requestAllFilesForServices(
            AtomicReference<StreamObserver<ServerReflectionRequest>> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            ServerReflectionResponse response) {
        for (ServiceResponse service : response.getListServicesResponse().getServiceList()) {
            makeRequest(
                    reqStream,
                    outstandingRequests,
                    ServerReflectionRequest.newBuilder()
                            .setFileContainingSymbol(service.getName())
                            .build());
        }
    }

    private void requestUnseenDependencyProtos(
            AtomicReference<StreamObserver<ServerReflectionRequest>> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            Map<String, DescriptorProtos.FileDescriptorProto> protosByFileName,
            FileDescriptorResponse response) {
        for (ByteString protoBytes : response.getFileDescriptorProtoList()) {
            DescriptorProtos.FileDescriptorProto protoDescriptor;
            try {
                protoDescriptor = DescriptorProtos.FileDescriptorProto.parseFrom(protoBytes);
            } catch (InvalidProtocolBufferException e) {
                log.warn("InvalidProtocolBufferException when parsing proto bytes... skipping", e);
                continue;
            }

            protosByFileName.put(protoDescriptor.getName(), protoDescriptor);

            for (String dependencyFileName : protoDescriptor.getDependencyList()) {
                if (!protosByFileName.containsKey(dependencyFileName)) {
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

    private Collection<Descriptors.FileDescriptor> compileProtos(
            Map<String, DescriptorProtos.FileDescriptorProto> protosByFileName) {
        // Find all "roots", where a root is a proto file for which another proto file does not depend on it
        Map<String, DescriptorProtos.FileDescriptorProto> rootsByFileName = Maps.newHashMap(protosByFileName);
        for (DescriptorProtos.FileDescriptorProto proto : protosByFileName.values()) {
            for (String dependencyFileName : proto.getDependencyList()) {
                rootsByFileName.remove(dependencyFileName);
            }
        }

        Map<String, Descriptors.FileDescriptor> compiledProtosByFileName = Maps.newHashMap();

        // Perform a postorder traversal (i.e. visit children first) from every root, compiling and storing the proto
        // file if it has not already been compiled
        TreeTraverser<DescriptorProtos.FileDescriptorProto> treeTraverser =
                new TreeTraverser<DescriptorProtos.FileDescriptorProto>() {
                    @Override
                    public Iterable<DescriptorProtos.FileDescriptorProto> children(
                            DescriptorProtos.FileDescriptorProto root) {
                        return root.getDependencyList()
                                .stream()
                                // Note: skip visiting dependencies if they have already been compiled
                                .filter(dependencyFileName -> !compiledProtosByFileName.containsKey(dependencyFileName))
                                .map(protosByFileName::get)
                                .collect(Collectors.toSet());
                    }
                };
        for (DescriptorProtos.FileDescriptorProto root : rootsByFileName.values()) {
            for (DescriptorProtos.FileDescriptorProto proto : treeTraverser.postOrderTraversal(root)) {
                Descriptors.FileDescriptor[] dependencies = proto.getDependencyList()
                        .stream()
                        .map(compiledProtosByFileName::get)
                        .toArray(Descriptors.FileDescriptor[]::new);

                Descriptors.FileDescriptor compiledProto;
                try {
                    compiledProto = Descriptors.FileDescriptor.buildFrom(proto, dependencies);
                } catch (Descriptors.DescriptorValidationException e) {
                    log.warn("Exception encountered when building proto... skipping", e);
                    continue;
                }

                compiledProtosByFileName.put(proto.getName(), compiledProto);
            }
        }

        return compiledProtosByFileName.values();
    }

    private void makeRequest(
            AtomicReference<StreamObserver<ServerReflectionRequest>> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            ServerReflectionRequest request) {
        outstandingRequests.add(request);
        reqStream.get().onNext(request);
    }

}
