/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.util.JsonFormat;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: re-index when the channel changes state from closed -> open
public final class ServiceIndexImpl extends AbstractService implements ServiceIndex {

    private static final Logger log = LoggerFactory.getLogger(ServiceIndexImpl.class);

    private final ReflectionChannel reflectionChannel;
    private final ScheduledExecutorService executorService;
    private final Duration failureDelay;

    private AtomicReference<ReflectionChannel.ReflectionCall> currentCall =
            new AtomicReference<>();
    private AtomicReference<Map<String, Descriptors.MethodDescriptor>> index =
            new AtomicReference<>(ImmutableMap.of());

    public ServiceIndexImpl(
            ReflectionChannel reflectionChannel, ScheduledExecutorService executorService, Duration failureDelay) {
        this.reflectionChannel = reflectionChannel;
        this.executorService = executorService;
        this.failureDelay = failureDelay;
    }

    @Override
    protected void doStart() {
        scheduleReflection(Duration.ZERO);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        ReflectionChannel.ReflectionCall call = currentCall.get();
        if (call != null) {
            call.cancel(new RuntimeException());
        }
        notifyStopped();
    }

    @Override
    public Optional<Descriptors.MethodDescriptor> getMethod(String fullMethodName) {
        return Optional.ofNullable(index.get().get(fullMethodName));
    }

    private void scheduleReflection(Duration scheduleDelay) {
        executorService.schedule(
                () -> {
                    if (!isRunning()) {
                        return;
                    }
                    ReflectionChannel.ReflectionCall lastCall = currentCall.getAndSet(
                            reflectionChannel.startCall(new IndexFromReflectionObserver()));
                    lastCall.cancel(new RuntimeException());
                },
                scheduleDelay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private class IndexFromReflectionObserver implements ReflectionChannel.ReflectionObserver {

        private Set<String> availableServices = Sets.newHashSet();
        private Map<String, DescriptorProtos.FileDescriptorProto> protoFiles = Maps.newHashMap();

        @Override
        public void onAvailableService(String serviceName) {
            availableServices.add(serviceName);
        }

        @Override
        public void onProtoFile(String fileName, DescriptorProtos.FileDescriptorProto proto) {
            protoFiles.put(fileName, proto);
        }

        @Override
        public void onComplete() {
            index.set(buildIndex(compileProtos()));
        }

        @Override
        public void onError(Throwable error) {
            log.warn("Error encountered receiving server reflection. Restart reflection. delay={}",
                    failureDelay, error);
            scheduleReflection(failureDelay);
        }


        private Collection<Descriptors.FileDescriptor> compileProtos() {
            // Find all "roots", where a root is a proto file for which another proto file does not depend on it
            Map<String, DescriptorProtos.FileDescriptorProto> rootsByFileName = Maps.newHashMap(protoFiles);
            for (DescriptorProtos.FileDescriptorProto proto : protoFiles.values()) {
                for (String dependencyFileName : proto.getDependencyList()) {
                    rootsByFileName.remove(dependencyFileName);
                }
            }

            Map<String, Descriptors.FileDescriptor> compiledProtosByFileName = Maps.newHashMap();

            // Perform a postorder traversal (i.e. visit children first) from every root, compiling and storing the proto
            // file if it has not already been compiled
            TreeTraverser<DescriptorProtos.FileDescriptorProto> treeTraverser = new TreeTraverser<DescriptorProtos.FileDescriptorProto>() {
                @Override
                public Iterable<DescriptorProtos.FileDescriptorProto> children(
                        DescriptorProtos.FileDescriptorProto root) {
                    return root.getDependencyList()
                            .stream()
                            // Note: skip visiting dependencies if they have already been compiled
                            .filter(dependencyFileName -> !compiledProtosByFileName.containsKey(dependencyFileName))
                            .map(protoFiles::get)
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

        private Map<String, Descriptors.MethodDescriptor> buildIndex(
                Collection<Descriptors.FileDescriptor> compiledProtos) {
            Map<String, Descriptors.MethodDescriptor> index = Maps.newHashMap();
            JsonFormat.TypeRegistry.Builder typeRegistryBuilder = JsonFormat.TypeRegistry.newBuilder();
            for (Descriptors.FileDescriptor compiledProto : compiledProtos) {
                typeRegistryBuilder.add(compiledProto.getMessageTypes());
                for (Descriptors.ServiceDescriptor service : compiledProto.getServices()) {
                    if (availableServices.contains(service.getFullName())) {
                        for (Descriptors.MethodDescriptor method : service.getMethods()) {
                            String methodFullName = String.format("%s/%s", service.getFullName(), method.getName());
                            index.put(methodFullName, method);
                        }
                    }
                }
            }
            return index;
        }
    }
}
