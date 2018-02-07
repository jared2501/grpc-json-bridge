# gRPC/JSON Bridge

A simple service that uses the [gRPC ServerReflection service](https://github.com/grpc/grpc/blob/master/src/proto/grpc/reflection/v1alpha/reflection.proto) to dynamically translate HTTP JSON requests into unary gRPC requests using the [canonical JSON format](https://developers.google.com/protocol-buffers/docs/proto3#json) for Protocol Buffers. Currently, only methods that have unary requests and return unary responses are supported.

For example, given a service that implements the following gRPC service:

```proto
syntax = "proto3";

package com.github.jared2501.foo;

service ExampleService {
    // An example method that takes a comma-separated list of numbers and returns the array
    rpc ExampleMethod (TestMessage) returns (TestMessage);
}

message MyRequest {
    string message = 1;
}

message MyResponse {
    repeated uint32 array = 1;
}
```

A curl request to the gRPC/JSON bridge such as the following:

```bash
curl -k -H 'Content-Type: application/json' \
  -d '{"message": "1,2,3"}' \
  http://grpc-json-bridge.local:8014/example/com.github.jared2501.foo.ExampleService/ExampleMethod
```

Would yield a response of:

```json
{"array": [1, 2, 3]}
```

The URL requested above follows the pattern:

```
http://${host}:${port}/${service-name}/${package}.{ServiceName}/${MethodName}
```

Where,

- `${host}:${port}`: the host/port where the gRPC/JSON Bridge is running on (see configuration section below).
- `${service-name}`: the name of the service to send the request to (see configuration section below).
- `${package.ServiceName}`: the fully qualified name of the gRPC service to invoke.
- `${MethodName}`: the method name to invoke on the specified gRPC service.

## Why Is This Useful?

A typical gRPC implementation uses Protocol Buffers delivered over an HTTP/2 connection (with required support for HTTP/2 trailers). Since Protocol Buffers are a non-self-describing binary format and HTTP/2 trailers are not supported by browser APIs this makes using gRPC from a browser particularly difficult.

To solve this issue, a couple solutions are available:

- [grpc-ecosystem/grpc-gateway](https://github.com/grpc-ecosystem/grpc-gateway): requires annotating your gRPC proto definitions with special annotations and then running a tool which generates Go code that you must run inside a Go server.
- [Envoy gRPC-JSON transcoder filter](https://www.envoyproxy.io/docs/envoy/latest/api-v1/http_filters/grpc_json_transcoder_filter): requires the gRPC proto definitions to be made available to Envoy.
- [grpc-web](https://github.com/improbable-eng/grpc-web): An implementation of the [gRPC web protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) that removes the requirement for an HTTP/2 connection and allows JSON to be submitted as "application/grpc-web+json". However, the use of JSON requires the downstream server to support JSON marshalling (which the default generators do not produce).

This repository offers a simple, quick solution that does not require changing your existing gRPC/Protobuf services and does not require the proto definitions to be made available statically to the server.

## How To Build/Run

For development purposes, the server can be run by running:

```bash
./gradlew idea
./gradlew grpc-json-bridge-server:run
```

To build a distribution, run:

```
./gradlew grpc-json-bridge-server:distTar
```

To run the distribution, untar it and then execute it:

```
cd ./grpc-json-bridge-server/build/
tar xzvf *.tgz
cd grpc-json-bridge-server*
./bin/grpc-json-bridge-server
```
