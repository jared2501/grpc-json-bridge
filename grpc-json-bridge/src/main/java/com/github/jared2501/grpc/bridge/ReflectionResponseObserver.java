/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Context;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ReflectionResponseObserver implements StreamObserver<ServerReflectionResponse> {

    private static final Logger log = LoggerFactory.getLogger(ReflectionResponseObserver.class);

    private final Set<ServerReflectionRequest> outstandingRequests = Sets.newHashSet();
    private final Map<String, FileDescriptorProto> protosByFileName = Maps.newHashMap();
    private final String serviceName;

    private Runnable onDoneHandler;
    private StreamObserver<ServerReflectionRequest> reqStream;
    private Context.CancellableContext context;

    private final Set<String> availableServices = Sets.newHashSet();
    private final Map<String, MethodDescriptor> availableMethodsByFullMethodName = Maps.newHashMap();
    private JsonFormat.TypeRegistry typeRegistry;

    public ReflectionResponseObserver(String serviceName) {
        this.serviceName = serviceName;
    }

    public void start(ServerReflectionGrpc.ServerReflectionStub serverReflection) {
        context = Context.CancellableContext.current().withCancellation();
        context.run(() -> reqStream = serverReflection.serverReflectionInfo(this));
        reqStream.onNext(ServerReflectionRequest.newBuilder()
                .setListServices("true")
                .build());
    }

    public void onDoneHandler(Runnable runnable) {
        this.onDoneHandler = runnable;
    }

    public void cancel() {
        context.cancel(new RuntimeException());
    }

    public JsonFormat.TypeRegistry getTypeRegistry() {
        checkState(typeRegistry != null, "ReflectionResponseObserver has not finished yet");
        return typeRegistry;
    }

    public Optional<MethodDescriptor> getAvailableMethod(String fullMethodName) {
        checkState(typeRegistry != null, "ReflectionResponseObserver has not finished yet");
        return Optional.ofNullable(availableMethodsByFullMethodName.get(fullMethodName));
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
                        protosByFileName,
                        response.getFileDescriptorResponse());
                break;
            default:
                log.error("Unexpected response case: {}", response.getMessageResponseCase());
                break;
        }

        if (outstandingRequests.isEmpty()) {
            JsonFormat.TypeRegistry.Builder typeRegistryBuilder = JsonFormat.TypeRegistry.newBuilder();
            Collection<FileDescriptor> compiledProtos = compileProtos(protosByFileName);
            for (FileDescriptor compiledProto : compiledProtos) {
                typeRegistryBuilder.add(compiledProto.getMessageTypes());
                for (ServiceDescriptor service : compiledProto.getServices()) {
                    if (availableServices.contains(service.getFullName())) {
                        for (MethodDescriptor method : service.getMethods()) {
                            String methodFullName = String.format("%s/%s", service.getFullName(), method.getName());
                            availableMethodsByFullMethodName.put(methodFullName, method);
                        }
                    }
                }
            }
            typeRegistry = typeRegistryBuilder.build();
            onDoneHandler.run();
        }
    }

    @Override
    public void onError(Throwable error) {
        log.error(
                "Throwable encountered when streaming handling reflecting for service {}",
                serviceName, error);
        // TODO(jnewman): handle errors here better
    }

    @Override
    public void onCompleted() {
        log.info("Reflection complete, service {} likely shutting down.", serviceName);
        // TODO(jnewman): handle this better
    }

    private void requestAllFilesForServices(
            StreamObserver<ServerReflectionRequest> reqStream,
            Set<ServerReflectionRequest> outstandingRequests,
            ServerReflectionResponse response) {
        for (ServiceResponse service : response.getListServicesResponse().getServiceList()) {
            availableServices.add(service.getName());
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

    private static Collection<FileDescriptor> compileProtos(
            Map<String, FileDescriptorProto> protosByFileName) {
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
        TreeTraverser<FileDescriptorProto> treeTraverser = new TreeTraverser<FileDescriptorProto>() {
            @Override
            public Iterable<FileDescriptorProto> children(FileDescriptorProto root) {
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
