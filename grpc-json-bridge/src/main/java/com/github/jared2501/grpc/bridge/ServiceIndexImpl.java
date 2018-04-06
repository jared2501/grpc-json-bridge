/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.util.JsonFormat;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceIndexImpl implements ServiceIndex {

    private static final Logger log = LoggerFactory.getLogger(ServiceIndexImpl.class);

    private final Set<String> availableServices = Sets.newHashSet();
    private final Map<String, FileDescriptorProto> protosByFileName = Maps.newHashMap();

    private final Map<String, AvailableMethod> availableMethodsByFullMethodName = Maps.newHashMap();
    private boolean isComplete = false;


    public void restart() {
        availableServices.clear();
        protosByFileName.clear();
    }

    public void addAvailableService(String serviceName) {
        availableServices.add(serviceName);
    }

    public void addProto(String fileName, FileDescriptorProto proto) {
        protosByFileName.put(fileName, proto);
    }

    public void complete() {
        isComplete = true;
        buildIndex(availableServices, compileProtos(protosByFileName));
    }

    public boolean isAvailable() {
        return isComplete;
    }

    public boolean containsProto(String fileName) {
        return protosByFileName.containsKey(fileName);
    }

    @Override
    public Optional<AvailableMethod> getMethod(String fullMethodName) {
        return Optional.ofNullable(availableMethodsByFullMethodName.get(fullMethodName));
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

    private void buildIndex(
            Set<String> availableServices,
            Collection<FileDescriptor> compiledProtos) {
        JsonFormat.TypeRegistry.Builder typeRegistryBuilder = JsonFormat.TypeRegistry.newBuilder();
        for (FileDescriptor compiledProto : compiledProtos) {
            typeRegistryBuilder.add(compiledProto.getMessageTypes());
            for (Descriptors.ServiceDescriptor service : compiledProto.getServices()) {
                if (availableServices.contains(service.getFullName())) {
                    for (Descriptors.MethodDescriptor method : service.getMethods()) {
                        String methodFullName = String.format("%s/%s", service.getFullName(), method.getName());
                        availableMethodsByFullMethodName.put(methodFullName, AvailableMethod.builder()
                                .methodDescriptor(method)
                                .typeRegistry(null) // TODO this??
                                .build());
                    }
                }
            }
        }
    }

}
