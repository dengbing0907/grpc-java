/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.observability.interceptors;

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.ObservabilityConfig;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A logging interceptor for {@code LoggingChannelProvider}.
 */
@Internal
public final class InternalLoggingChannelInterceptor implements ClientInterceptor {
  private static final Logger logger = Logger
      .getLogger(InternalLoggingChannelInterceptor.class.getName());

  private final LogHelper helper;

  public interface Factory {
    ClientInterceptor create();
  }

  public static class FactoryImpl implements Factory {
    private final Sink sink;
    private final LogHelper helper;

    /** Create the {@link Factory} we need to create our {@link ClientInterceptor}s. */
    public FactoryImpl(Sink sink, Map<String, String> locationTags, Map<String, String> customTags,
        ObservabilityConfig observabilityConfig) {
      this.sink = sink;
      this.helper = new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER, locationTags, customTags,
          observabilityConfig);
    }

    @Override
    public ClientInterceptor create() {
      return new InternalLoggingChannelInterceptor(helper);
    }

    /**
     * Closes the sink instance.
     */
    public void close() {
      if (sink != null) {
        sink.close();
      }
    }
  }

  private InternalLoggingChannelInterceptor(LogHelper helper) {
    this.helper = helper;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {

    final AtomicLong seq = new AtomicLong(1);
    final String rpcId = UUID.randomUUID().toString();
    final String authority = next.authority();
    final String serviceName = method.getServiceName();
    final String methodName = method.getBareMethodName();
    // Get the stricter deadline to calculate the timeout once the call starts
    final Deadline deadline = LogHelper.min(callOptions.getDeadline(),
        Context.current().getDeadline());

    // TODO (dnvindhya): implement isMethodToBeLogged() to check for methods to be logged
    // according to config. Until then always return true.
    if (!helper.isMethodToBeLogged(method.getFullMethodName())) {
      return next.newCall(method, callOptions);
    }

    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        // Event: EventType.GRPC_CALL_REQUEST_HEADER
        // The timeout should reflect the time remaining when the call is started, so compute
        // remaining time here.
        final Duration timeout = deadline == null ? null
            : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));

        try {
          helper.logRequestHeader(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              timeout,
              headers,
              EventLogger.LOGGER_CLIENT,
              rpcId,
              null);
        } catch (Exception e) {
          // Catching generic exceptions instead of specific ones for all the events.
          // This way we can catch both expected and unexpected exceptions instead of re-throwing
          // exceptions to callers which will lead to RPC getting aborted.
          // Expected exceptions to be caught:
          // 1. IllegalArgumentException
          // 2. NullPointerException
          logger.log(Level.SEVERE, "Unable to log request header", e);
        }

        Listener<RespT> observabilityListener =
            new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Event: EventType.GRPC_CALL_RESPONSE_MESSAGE
                try {
                  helper.logRpcMessage(
                      seq.getAndIncrement(),
                      serviceName,
                      methodName,
                      EventType.GRPC_CALL_RESPONSE_MESSAGE,
                      message,
                      EventLogger.LOGGER_CLIENT,
                      rpcId);
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log response message", e);
                }
                super.onMessage(message);
              }

              @Override
              public void onHeaders(Metadata headers) {
                // Event: EventType.GRPC_CALL_RESPONSE_HEADER
                try {
                  helper.logResponseHeader(
                      seq.getAndIncrement(),
                      serviceName,
                      methodName,
                      headers,
                      EventLogger.LOGGER_CLIENT,
                      rpcId,
                      LogHelper.getPeerAddress(getAttributes()));
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log response header", e);
                }
                super.onHeaders(headers);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                // Event: EventType.GRPC_CALL_TRAILER
                try {
                  helper.logTrailer(
                      seq.getAndIncrement(),
                      serviceName,
                      methodName,
                      status,
                      trailers,
                      EventLogger.LOGGER_CLIENT,
                      rpcId,
                      LogHelper.getPeerAddress(getAttributes()));
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log trailer", e);
                }
                super.onClose(status, trailers);
              }
            };
        super.start(observabilityListener, headers);
      }

      @Override
      public void sendMessage(ReqT message) {
        // Event: EventType.GRPC_CALL_REQUEST_MESSAGE
        try {
          helper.logRpcMessage(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventType.GRPC_CALL_REQUEST_MESSAGE,
              message,
              EventLogger.LOGGER_CLIENT,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log request message", e);
        }
        super.sendMessage(message);
      }

      @Override
      public void halfClose() {
        // Event: EventType.GRPC_CALL_HALF_CLOSE
        try {
          helper.logHalfClose(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventLogger.LOGGER_CLIENT,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log half close", e);
        }
        super.halfClose();
      }

      @Override
      public void cancel(String message, Throwable cause) {
        // Event: EventType.GRPC_CALL_CANCEL
        try {
          helper.logCancel(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventLogger.LOGGER_CLIENT,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log cancel", e);
        }
        super.cancel(message, cause);
      }
    };
  }
}
