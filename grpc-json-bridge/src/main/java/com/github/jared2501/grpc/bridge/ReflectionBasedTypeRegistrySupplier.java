/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link TypeRegistrySupplier} that uses the {@link ServerReflectionGrpc ServerReflectionGrpc service} to build a
 * {@link com.google.protobuf.util.JsonFormat.TypeRegistry} of types that are required to communicate with a specified
 * service.
 * <p>
 * TypeRegistries that are built will be cached for a specified period of time.
 */
class ReflectionBasedTypeRegistrySupplier implements TypeRegistrySupplier {

    private static final Logger log = LoggerFactory.getLogger(ReflectionBasedTypeRegistrySupplier.class);

    private final AsyncLoadingCache<String, JsonFormat.TypeRegistry> typeRegistries;

    ReflectionBasedTypeRegistrySupplier(
            ChannelProvider stubProvider, long expiryDuration, TimeUnit expiryTimeUnit) {
        this.typeRegistries = Caffeine.newBuilder()
                .expireAfterWrite(expiryDuration, expiryTimeUnit)
                .buildAsync(new AsyncCacheLoader<String, JsonFormat.TypeRegistry>() {
                    @Nonnull
                    @Override
                    public CompletableFuture<JsonFormat.TypeRegistry> asyncLoad(String serviceName, Executor executor) {
                        return getTypeRegistry(
                                serviceName, ServerReflectionGrpc.newStub(stubProvider.get(serviceName)));
                    }
                });
    }

    @Override
    public CompletableFuture<JsonFormat.TypeRegistry> getTypeRegistry(String serviceName) {
        return typeRegistries.get(serviceName);
    }

    private static CompletableFuture<JsonFormat.TypeRegistry> getTypeRegistry(
            String serviceName, ServerReflectionGrpc.ServerReflectionStub reflection) {
        CompletableFuture<JsonFormat.TypeRegistry> result = new CompletableFuture<>();

        Set<ServerReflectionRequest> outstandingRequests = Sets.newHashSet();
        Map<String, FileDescriptorProto> protosByFileName = Maps.newHashMap();

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
                                requestAllFilesForServices(reqStream.get(), outstandingRequests, response);
                                break;
                            case FILE_DESCRIPTOR_RESPONSE:
                                requestUnseenDependencyProtos(
                                        reqStream.get(),
                                        outstandingRequests,
                                        protosByFileName,
                                        response.getFileDescriptorResponse());
                                break;
                            default:
                                log.error("Unexpected response case: {}", response.getMessageResponseCase());
                                break;
                        }

                        if (outstandingRequests.isEmpty()) {
                            Collection<FileDescriptor> compiledProtos = compileProtos(protosByFileName);
                            JsonFormat.TypeRegistry.Builder typeRegistry = JsonFormat.TypeRegistry.newBuilder();
                            for (FileDescriptor compiledProto : compiledProtos) {
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

    private static void requestAllFilesForServices(
            StreamObserver<ServerReflectionRequest> reqStream,
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

    private static void requestUnseenDependencyProtos(
            StreamObserver<ServerReflectionRequest> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            Map<String, FileDescriptorProto> protosByFileName,
            FileDescriptorResponse response) {
        for (ByteString protoBytes : response.getFileDescriptorProtoList()) {
            FileDescriptorProto protoDescriptor;
            try {
                protoDescriptor = FileDescriptorProto.parseFrom(protoBytes);
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

    private static Collection<FileDescriptor> compileProtos(Map<String, FileDescriptorProto> protosByFileName) {
        // Find all "roots", where a root is a proto file for which another proto file does not depend on it
        Map<String, FileDescriptorProto> rootsByFileName = Maps.newHashMap(protosByFileName);
        for (FileDescriptorProto proto : protosByFileName.values()) {
            for (String dependencyFileName : proto.getDependencyList()) {
                rootsByFileName.remove(dependencyFileName);
            }
        }

        Map<String, FileDescriptor> compiledProtosByFileName = Maps.newHashMap();

        // Perform a postorder traversal (i.e. visit children first) from every root, compiling and storing the proto
        // file if it has not already been compiled
        TreeTraverser<FileDescriptorProto> treeTraverser =
                new TreeTraverser<FileDescriptorProto>() {
                    @Override
                    public Iterable<FileDescriptorProto> children(
                            FileDescriptorProto root) {
                        return root.getDependencyList()
                                .stream()
                                // Note: skip visiting dependencies if they have already been compiled
                                .filter(dependencyFileName -> !compiledProtosByFileName.containsKey(dependencyFileName))
                                .map(protosByFileName::get)
                                .collect(Collectors.toSet());
                    }
                };
        for (FileDescriptorProto root : rootsByFileName.values()) {
            for (FileDescriptorProto proto : treeTraverser.postOrderTraversal(root)) {
                FileDescriptor[] dependencies = proto.getDependencyList()
                        .stream()
                        .map(compiledProtosByFileName::get)
                        .toArray(FileDescriptor[]::new);

                FileDescriptor compiledProto;
                try {
                    compiledProto = FileDescriptor.buildFrom(proto, dependencies);
                } catch (DescriptorValidationException e) {
                    log.warn("Exception encountered when building proto... skipping", e);
                    continue;
                }

                compiledProtosByFileName.put(proto.getName(), compiledProto);
            }
        }

        return compiledProtosByFileName.values();
    }

    private static void makeRequest(
            StreamObserver<ServerReflectionRequest> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            ServerReflectionRequest request) {
        outstandingRequests.add(request);
        reqStream.onNext(request);
    }

}
