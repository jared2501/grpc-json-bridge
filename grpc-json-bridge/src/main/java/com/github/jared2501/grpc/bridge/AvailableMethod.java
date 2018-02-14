/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge;

import com.google.protobuf.Descriptors;
import com.google.protobuf.util.JsonFormat;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

@Value.Immutable
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
public interface AvailableMethod {

    /** The {@link Descriptors.MethodDescriptor} for this method. */
    Descriptors.MethodDescriptor methodDescriptor();

    /**
     * A {@link JsonFormat.TypeRegistry} that can be used to serialize and deserialize JSON to communicate with this
     * method.
     */
    JsonFormat.TypeRegistry typeRegistry();

    final class Builder extends ImmutableAvailableMethod.Builder {}

    static Builder builder() {
        return new Builder();
    }
}
