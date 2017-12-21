/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.github.jared2501.grpc.bridge.test;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class TestServiceImplTest {
    private static final TestMessage MESSAGE = TestMessage.newBuilder().setMessage("message").build();

    @Mock
    private StreamObserver<TestMessage> streamObs;

    private TestServiceImpl testService;

    @BeforeEach
    void before() {
        MockitoAnnotations.initMocks(this);
        testService = new TestServiceImpl();
    }

    @Test
    void unaryReqUnaryResp() {
        testService.unaryReqUnaryResp(MESSAGE, streamObs);
        verify(streamObs).onNext(MESSAGE);
        verify(streamObs).onCompleted();
    }

    @Test
    void unaryReqStreamResp() {
        testService.unaryReqStreamResp(MESSAGE, streamObs);
        verify(streamObs).onNext(MESSAGE);
        verify(streamObs).onCompleted();
    }

    @Test
    void streamReqUnaryResp() {
        StreamObserver<TestMessage> req = testService.streamReqUnaryResp(streamObs);
        req.onNext(MESSAGE);
        req.onNext(MESSAGE);
        req.onCompleted();
        verify(streamObs).onNext(MESSAGE);
        verify(streamObs).onCompleted();
    }

    @Test
    void streamReqStreamResp() {
        StreamObserver<TestMessage> req = testService.streamReqStreamResp(streamObs);
        req.onNext(MESSAGE);
        req.onNext(MESSAGE);
        req.onCompleted();
        verify(streamObs, times(2)).onNext(MESSAGE);
        verify(streamObs).onCompleted();
    }
}
