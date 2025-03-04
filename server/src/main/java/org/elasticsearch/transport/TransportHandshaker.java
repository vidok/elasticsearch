/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.transport;

import org.elasticsearch.Build;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.metrics.CounterMetric;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.UpdateForV9;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends and receives transport-level connection handshakes. This class will send the initial handshake, manage state/timeouts while the
 * handshake is in transit, and handle the eventual response.
 */
final class TransportHandshaker {

    /*
     * The transport-level handshake allows the node that opened the connection to determine the newest protocol version with which it can
     * communicate with the remote node. Each node sends its maximum acceptable protocol version to the other, but the responding node
     * ignores the body of the request. After the handshake, the OutboundHandler uses the min(local,remote) protocol version for all later
     * messages.
     *
     * This version supports three handshake protocols, v6080099, v7170099 and v8800000, which respectively have the same message structure
     * as the transport protocols of v6.8.0, v7.17.0, and v8.18.0. This node only sends v7170099 requests, but it can send a valid response
     * to any v6080099 or v8800000 requests that it receives.
     *
     * Note that these are not really TransportVersion constants as used elsewhere in ES, they're independent things that just happen to be
     * stored in the same location in the message header and which roughly match the same ID numbering scheme. Older versions of ES did
     * rely on them matching the real transport protocol (which itself matched the release version numbers), but these days that's no longer
     * true.
     *
     * Here are some example messages, broken down to show their structure:
     *
     * ## v6080099 Request:
     *
     * 45 53                            -- 'ES' marker
     * 00 00 00 34                      -- total message length
     *    00 00 00 00 00 00 00 01       -- request ID
     *    08                            -- status flags (0b1000 == handshake request)
     *    00 5c c6 63                   -- handshake protocol version (0x5cc663 == 6080099)
     *    00                            -- no request headers [1]
     *    00                            -- no response headers [1]
     *    01                            -- one feature [2]
     *       06                         -- feature name length
     *          78 2d 70 61 63 6b       -- feature name 'x-pack'
     *    16                            -- action string size
     *       69 6e 74 65 72 6e 61 6c    }
     *       3a 74 63 70 2f 68 61 6e    }- ASCII representation of HANDSHAKE_ACTION_NAME
     *       64 73 68 61 6b 65          }
     *    00                            -- no parent task ID [3]
     *    04                            -- payload length
     *       8b d5 b5 03                -- max acceptable protocol version (vInt: 00000011 10110101 11010101 10001011 == 7170699)
     *
     * ## v6080099 Response:
     *
     * 45 53                            -- 'ES' marker
     * 00 00 00 13                      -- total message length
     *    00 00 00 00 00 00 00 01       -- request ID (copied from request)
     *    09                            -- status flags (0b1001 == handshake response)
     *    00 5c c6 63                   -- handshake protocol version (0x5cc663 == 6080099, copied from request)
     *    00                            -- no request headers [1]
     *    00                            -- no response headers [1]
     *    c3 f9 eb 03                   -- max acceptable protocol version (vInt: 00000011 11101011 11111001 11000011 == 8060099)
     *
     *
     * ## v7170099 and v8800000 Requests:
     *
     * 45 53                            -- 'ES' marker
     * 00 00 00 31                      -- total message length
     *    00 00 00 00 00 00 00 01       -- request ID
     *    08                            -- status flags (0b1000 == handshake request)
     *    00 6d 68 33                   -- handshake protocol version (0x6d6833 == 7170099)
     *    00 00 00 1a                   -- length of variable portion of header
     *       00                         -- no request headers [1]
     *       00                         -- no response headers [1]
     *       00                         -- no features [2]
     *       16                         -- action string size
     *       69 6e 74 65 72 6e 61 6c    }
     *       3a 74 63 70 2f 68 61 6e    }- ASCII representation of HANDSHAKE_ACTION_NAME
     *       64 73 68 61 6b 65          }
     *    00                            -- no parent task ID [3]
     *    04                            -- payload length
     *       c3 f9 eb 03                -- max acceptable protocol version (vInt: 00000011 11101011 11111001 11000011 == 8060099)
     *
     * ## v7170099 and v8800000 Responses:
     *
     * 45 53                            -- 'ES' marker
     * 00 00 00 17                      -- total message length
     *    00 00 00 00 00 00 00 01       -- request ID (copied from request)
     *    09                            -- status flags (0b1001 == handshake response)
     *    00 6d 68 33                   -- handshake protocol version (0x6d6833 == 7170099, copied from request)
     *    00 00 00 02                   -- length of following variable portion of header
     *       00                         -- no request headers [1]
     *       00                         -- no response headers [1]
     *    c3 f9 eb 03                   -- max acceptable protocol version (vInt: 00000011 11101011 11111001 11000011 == 8060099)
     *
     * [1] Thread context headers should be empty; see org.elasticsearch.common.util.concurrent.ThreadContext.ThreadContextStruct.writeTo
     *     for their structure.
     * [2] A list of strings, which can safely be ignored
     * [3] Parent task ID should be empty; see org.elasticsearch.tasks.TaskId.writeTo for its structure.
     */

    static final TransportVersion V7_HANDSHAKE_VERSION = TransportVersion.fromId(6_08_00_99);
    static final TransportVersion V8_HANDSHAKE_VERSION = TransportVersion.fromId(7_17_00_99);
    static final TransportVersion V9_HANDSHAKE_VERSION = TransportVersion.fromId(8_800_00_0);
    static final Set<TransportVersion> ALLOWED_HANDSHAKE_VERSIONS = Set.of(
        V7_HANDSHAKE_VERSION,
        V8_HANDSHAKE_VERSION,
        V9_HANDSHAKE_VERSION
    );

    static final String HANDSHAKE_ACTION_NAME = "internal:tcp/handshake";
    private final ConcurrentMap<Long, HandshakeResponseHandler> pendingHandshakes = new ConcurrentHashMap<>();
    private final CounterMetric numHandshakes = new CounterMetric();

    private final TransportVersion version;
    private final ThreadPool threadPool;
    private final HandshakeRequestSender handshakeRequestSender;
    private final boolean ignoreDeserializationErrors;

    TransportHandshaker(
        TransportVersion version,
        ThreadPool threadPool,
        HandshakeRequestSender handshakeRequestSender,
        boolean ignoreDeserializationErrors
    ) {
        this.version = version;
        this.threadPool = threadPool;
        this.handshakeRequestSender = handshakeRequestSender;
        this.ignoreDeserializationErrors = ignoreDeserializationErrors;
    }

    void sendHandshake(
        long requestId,
        DiscoveryNode node,
        TcpChannel channel,
        TimeValue timeout,
        ActionListener<TransportVersion> listener
    ) {
        numHandshakes.inc();
        final HandshakeResponseHandler handler = new HandshakeResponseHandler(requestId, listener);
        pendingHandshakes.put(requestId, handler);
        channel.addCloseListener(
            ActionListener.running(() -> handler.handleLocalException(new TransportException("handshake failed because connection reset")))
        );
        boolean success = false;
        try {
            handshakeRequestSender.sendRequest(node, channel, requestId, V8_HANDSHAKE_VERSION);

            threadPool.schedule(
                () -> handler.handleLocalException(new ConnectTransportException(node, "handshake_timeout[" + timeout + "]")),
                timeout,
                threadPool.generic()
            );
            success = true;
        } catch (Exception e) {
            handler.handleLocalException(new ConnectTransportException(node, "failure to send " + HANDSHAKE_ACTION_NAME, e));
        } finally {
            if (success == false) {
                TransportResponseHandler<?> removed = pendingHandshakes.remove(requestId);
                assert removed == null : "Handshake should not be pending if exception was thrown";
            }
        }
    }

    void handleHandshake(TransportChannel channel, long requestId, StreamInput stream) throws IOException {
        try {
            // Must read the handshake request to exhaust the stream
            new HandshakeRequest(stream);
        } catch (Exception e) {
            assert ignoreDeserializationErrors : e;
            throw e;
        }
        final int nextByte = stream.read();
        if (nextByte != -1) {
            final IllegalStateException exception = new IllegalStateException(
                "Handshake request not fully read for requestId ["
                    + requestId
                    + "], action ["
                    + TransportHandshaker.HANDSHAKE_ACTION_NAME
                    + "], available ["
                    + stream.available()
                    + "]; resetting"
            );
            assert ignoreDeserializationErrors : exception;
            throw exception;
        }
        channel.sendResponse(new HandshakeResponse(this.version, Build.current().version()));
    }

    TransportResponseHandler<HandshakeResponse> removeHandlerForHandshake(long requestId) {
        return pendingHandshakes.remove(requestId);
    }

    int getNumPendingHandshakes() {
        return pendingHandshakes.size();
    }

    long getNumHandshakes() {
        return numHandshakes.count();
    }

    private class HandshakeResponseHandler implements TransportResponseHandler<HandshakeResponse> {

        private final long requestId;
        private final ActionListener<TransportVersion> listener;
        private final AtomicBoolean isDone = new AtomicBoolean(false);

        private HandshakeResponseHandler(long requestId, ActionListener<TransportVersion> listener) {
            this.requestId = requestId;
            this.listener = listener;
        }

        @Override
        public HandshakeResponse read(StreamInput in) throws IOException {
            return new HandshakeResponse(in);
        }

        @Override
        public Executor executor() {
            return TransportResponseHandler.TRANSPORT_WORKER;
        }

        @Override
        public void handleResponse(HandshakeResponse response) {
            if (isDone.compareAndSet(false, true)) {
                TransportVersion responseVersion = response.transportVersion;
                if (TransportVersion.isCompatible(responseVersion) == false) {
                    listener.onFailure(
                        new IllegalStateException(
                            "Received message from unsupported version: ["
                                + responseVersion
                                + "] minimal compatible version is: ["
                                + TransportVersions.MINIMUM_COMPATIBLE
                                + "]"
                        )
                    );
                } else {
                    listener.onResponse(TransportVersion.min(TransportHandshaker.this.version, response.getTransportVersion()));
                }
            }
        }

        @Override
        public void handleException(TransportException e) {
            if (isDone.compareAndSet(false, true)) {
                listener.onFailure(new IllegalStateException("handshake failed", e));
            }
        }

        void handleLocalException(TransportException e) {
            if (removeHandlerForHandshake(requestId) != null && isDone.compareAndSet(false, true)) {
                listener.onFailure(e);
            }
        }
    }

    static final class HandshakeRequest extends TransportRequest {

        /**
         * The {@link TransportVersion#current()} of the requesting node.
         */
        final TransportVersion transportVersion;

        /**
         * The {@link Build#version()} of the requesting node, as a {@link String}, for better reporting of handshake failures due to
         * an incompatible version.
         */
        final String releaseVersion;

        HandshakeRequest(TransportVersion transportVersion, String releaseVersion) {
            this.transportVersion = Objects.requireNonNull(transportVersion);
            this.releaseVersion = Objects.requireNonNull(releaseVersion);
        }

        @UpdateForV9(owner = UpdateForV9.Owner.CORE_INFRA) // remainingMessage == null is invalid in v9
        HandshakeRequest(StreamInput streamInput) throws IOException {
            super(streamInput);
            BytesReference remainingMessage;
            try {
                remainingMessage = streamInput.readSlicedBytesReference();
            } catch (EOFException e) {
                remainingMessage = null;
            }
            if (remainingMessage == null) {
                transportVersion = null;
                releaseVersion = null;
            } else {
                try (StreamInput messageStreamInput = remainingMessage.streamInput()) {
                    this.transportVersion = TransportVersion.readVersion(messageStreamInput);
                    if (streamInput.getTransportVersion().onOrAfter(V9_HANDSHAKE_VERSION)) {
                        this.releaseVersion = messageStreamInput.readString();
                    } else {
                        this.releaseVersion = this.transportVersion.toReleaseVersion();
                    }
                }
            }
        }

        @Override
        public void writeTo(StreamOutput streamOutput) throws IOException {
            super.writeTo(streamOutput);
            assert transportVersion != null;
            try (BytesStreamOutput messageStreamOutput = new BytesStreamOutput(1024)) {
                TransportVersion.writeVersion(transportVersion, messageStreamOutput);
                if (streamOutput.getTransportVersion().onOrAfter(V9_HANDSHAKE_VERSION)) {
                    messageStreamOutput.writeString(releaseVersion);
                } // else we just send the transport version and rely on a best-effort mapping to release versions
                BytesReference reference = messageStreamOutput.bytes();
                streamOutput.writeBytesReference(reference);
            }
        }
    }

    /**
     * A response to a low-level transport handshake, carrying information about the version of the responding node.
     */
    static final class HandshakeResponse extends TransportResponse {

        /**
         * The {@link TransportVersion#current()} of the responding node.
         */
        private final TransportVersion transportVersion;

        /**
         * The {@link Build#version()} of the responding node, as a {@link String}, for better reporting of handshake failures due to
         * an incompatible version.
         */
        private final String releaseVersion;

        HandshakeResponse(TransportVersion transportVersion, String releaseVersion) {
            this.transportVersion = Objects.requireNonNull(transportVersion);
            this.releaseVersion = Objects.requireNonNull(releaseVersion);
        }

        HandshakeResponse(StreamInput in) throws IOException {
            super(in);
            transportVersion = TransportVersion.readVersion(in);
            if (in.getTransportVersion().onOrAfter(V9_HANDSHAKE_VERSION)) {
                releaseVersion = in.readString();
            } else {
                releaseVersion = transportVersion.toReleaseVersion();
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            TransportVersion.writeVersion(transportVersion, out);
            if (out.getTransportVersion().onOrAfter(V9_HANDSHAKE_VERSION)) {
                out.writeString(releaseVersion);
            } // else we just send the transport version and rely on a best-effort mapping to release versions
        }

        /**
         * @return the {@link TransportVersion#current()} of the responding node.
         */
        TransportVersion getTransportVersion() {
            return transportVersion;
        }

        /**
         * @return the {@link Build#version()} of the responding node, as a {@link String}, for better reporting of handshake failures due
         * to an incompatible version.
         */
        String getReleaseVersion() {
            return releaseVersion;
        }
    }

    @FunctionalInterface
    interface HandshakeRequestSender {
        /**
         * @param node                      The (expected) remote node, for error reporting and passing to
         *                                  {@link TransportMessageListener#onRequestSent}.
         * @param channel                   The TCP channel to use to send the handshake request.
         * @param requestId                 The transport request ID, for matching up the response.
         * @param handshakeTransportVersion The {@link TransportVersion} to use for the handshake request, which will be
         *                                  {@link TransportHandshaker#V8_HANDSHAKE_VERSION} in production.
         */
        void sendRequest(DiscoveryNode node, TcpChannel channel, long requestId, TransportVersion handshakeTransportVersion)
            throws IOException;
    }
}
