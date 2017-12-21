/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge.test;

import io.grpc.stub.StreamObserver;

public final class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
    @Override
    public void unaryReqUnaryResp(TestMessage request, StreamObserver<TestMessage> responseObserver) {
        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }

    @Override
    public void unaryReqStreamResp(TestMessage request, StreamObserver<TestMessage> responseObserver) {
        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<TestMessage> streamReqUnaryResp(StreamObserver<TestMessage> responseObserver) {
        return new StreamObserver<TestMessage>() {

            private boolean done = false;

            @Override
            public void onNext(TestMessage value) {
                if (done) {
                    return;
                }
                done = true;
                responseObserver.onNext(value);
                responseObserver.onCompleted();
            }

            @Override
            public void onError(Throwable error) {
                if (done) {
                    return;
                }
                responseObserver.onError(error);
            }

            @Override
            public void onCompleted() {
                if (done) {
                    return;
                }
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<TestMessage> streamReqStreamResp(StreamObserver<TestMessage> responseObserver) {
        return new StreamObserver<TestMessage>() {
            @Override
            public void onNext(TestMessage value) {
                responseObserver.onNext(value);
            }

            @Override
            public void onError(Throwable error) {
                responseObserver.onError(error);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }
}
