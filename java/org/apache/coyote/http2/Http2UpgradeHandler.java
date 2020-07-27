/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.WebConnection;

import org.apache.coyote.Adapter;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.ProtocolException;
import org.apache.coyote.Request;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.coyote.http2.HpackEncoder.State;
import org.apache.coyote.http2.Http2Parser.Input;
import org.apache.coyote.http2.Http2Parser.Output;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.codec.binary.Base64;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * This represents an HTTP/2 connection from a client to Tomcat. It is designed
 * on the basis that there will never be more than one thread performing I/O at
 * a time.
 * <br>
 * For reading, this implementation is blocking within frames and non-blocking
 * between frames.
 * <br>
 * Note:
 * <ul>
 * <li>You will need to nest an &lt;UpgradeProtocol
 *     className="org.apache.coyote.http2.Http2Protocol" /&gt; element inside
 *     a TLS enabled Connector element in server.xml to enable HTTP/2 support.
 *     </li>
 * </ul>
 */
class Http2UpgradeHandler extends AbstractStream implements InternalHttpUpgradeHandler,
        Input, Output {

    protected static final Log log = LogFactory.getLog(Http2UpgradeHandler.class);
    protected static final StringManager sm = StringManager.getManager(Http2UpgradeHandler.class);

    private static final AtomicInteger connectionIdGenerator = new AtomicInteger(0);
    private static final Integer STREAM_ID_ZERO = Integer.valueOf(0);

    protected static final int FLAG_END_OF_STREAM = 1;
    protected static final int FLAG_END_OF_HEADERS = 4;

    protected static final byte[] PING = { 0x00, 0x00, 0x08, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00};
    protected static final byte[] PING_ACK = { 0x00, 0x00, 0x08, 0x06, 0x01, 0x00, 0x00, 0x00, 0x00 };

    protected static final byte[] SETTINGS_ACK = { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 };

    protected static final byte[] GOAWAY = { 0x07, 0x00, 0x00, 0x00, 0x00, 0x00 };

    private static final String HTTP2_SETTINGS_HEADER = "HTTP2-Settings";

    private static final HeaderSink HEADER_SINK = new HeaderSink();

    protected final String connectionId;

    protected final Http2Protocol protocol;
    private final Adapter adapter;
    protected volatile SocketWrapperBase<?> socketWrapper;
    private volatile SSLSupport sslSupport;

    private volatile Http2Parser parser;

    // Simple state machine (sequence of states)
    private AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.NEW);
    private volatile long pausedNanoTime = Long.MAX_VALUE;

    /**
     * Remote settings are settings defined by the client and sent to Tomcat
     * that Tomcat must use when communicating with the client.
     */
    private final ConnectionSettingsRemote remoteSettings;
    /**
     * Local settings are settings defined by Tomcat and sent to the client that
     * the client must use when communicating with Tomcat.
     */
    protected final ConnectionSettingsLocal localSettings;

    private HpackDecoder hpackDecoder;
    private HpackEncoder hpackEncoder;

    private final Map<Integer,Stream> streams = new ConcurrentHashMap<>();
    protected final AtomicInteger activeRemoteStreamCount = new AtomicInteger(0);
    // Start at -1 so the 'add 2' logic in closeIdleStreams() works
    private volatile int maxActiveRemoteStreamId = -1;
    private volatile int maxProcessedStreamId;
    private final AtomicInteger nextLocalStreamId = new AtomicInteger(2);
    private final PingManager pingManager = getPingManager();
    private volatile int newStreamsSinceLastPrune = 0;
    private final Map<AbstractStream, BacklogTracker> backLogStreams = new ConcurrentHashMap<>();
    private long backLogSize = 0;
    // The time at which the connection will timeout unless data arrives before
    // then. -1 means no timeout.
    private volatile long connectionTimeout = -1;

    // Stream concurrency control
    private AtomicInteger streamConcurrency = null;
    private Queue<StreamRunnable> queuedRunnable = null;

    // Track 'overhead' frames vs 'request/response' frames
    private final AtomicLong overheadCount = new AtomicLong(-10);
    private volatile int lastNonFinalDataPayload;
    private volatile int lastWindowUpdate;


    Http2UpgradeHandler(Http2Protocol protocol, Adapter adapter, Request coyoteRequest) {
        super (STREAM_ID_ZERO);
        this.protocol = protocol;
        this.adapter = adapter;
        this.connectionId = Integer.toString(connectionIdGenerator.getAndIncrement());

        lastNonFinalDataPayload = protocol.getOverheadDataThreshold() * 2;
        lastWindowUpdate = protocol.getOverheadWindowUpdateThreshold() * 2;

        remoteSettings = new ConnectionSettingsRemote(connectionId);
        localSettings = new ConnectionSettingsLocal(connectionId);

        localSettings.set(Setting.MAX_CONCURRENT_STREAMS, protocol.getMaxConcurrentStreams());
        localSettings.set(Setting.INITIAL_WINDOW_SIZE, protocol.getInitialWindowSize());

        pingManager.initiateDisabled = protocol.getInitiatePingDisabled();

        // Initial HTTP request becomes stream 1.
        if (coyoteRequest != null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.upgrade", connectionId));
            }
            Integer key = Integer.valueOf(1);
            Stream stream = new Stream(key, this, coyoteRequest);
            streams.put(key, stream);
            maxActiveRemoteStreamId = 1;
            activeRemoteStreamCount.set(1);
            maxProcessedStreamId = 1;
        }
    }


    protected PingManager getPingManager() {
        return new PingManager();
    }


    @Override
    public void init(WebConnection webConnection) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.init", connectionId, connectionState.get()));
        }

        if (!connectionState.compareAndSet(ConnectionState.NEW, ConnectionState.CONNECTED)) {
            return;
        }

        // Init concurrency control if needed
        if (protocol.getMaxConcurrentStreamExecution() < localSettings.getMaxConcurrentStreams()) {
            streamConcurrency = new AtomicInteger(0);
            queuedRunnable = new ConcurrentLinkedQueue<>();
        }

        parser = getParser(connectionId);

        Stream stream = null;

        socketWrapper.setReadTimeout(protocol.getReadTimeout());
        socketWrapper.setWriteTimeout(protocol.getWriteTimeout());

        if (webConnection != null) {
            // HTTP/2 started via HTTP upgrade.
            // The initial HTTP/1.1 request is available as Stream 1.

            try {
                // Process the initial settings frame
                stream = getStream(1, true);
                String base64Settings = stream.getCoyoteRequest().getHeader(HTTP2_SETTINGS_HEADER);
                byte[] settings = Base64.decodeBase64URLSafe(base64Settings);

                // Settings are only valid on stream 0
                FrameType.SETTINGS.check(0, settings.length);

                for (int i = 0; i < settings.length % 6; i++) {
                    int id = ByteUtil.getTwoBytes(settings, i * 6);
                    long value = ByteUtil.getFourBytes(settings, (i * 6) + 2);
                    remoteSettings.set(Setting.valueOf(id), value);
                }
            } catch (Http2Exception e) {
                throw new ProtocolException(
                        sm.getString("upgradeHandler.upgrade.fail", connectionId));
            }
        }

        // Send the initial settings frame
        writeSettings();

        // Make sure the client has sent a valid connection preface before we
        // send the response to the original request over HTTP/2.
        try {
            parser.readConnectionPreface(webConnection, stream);
        } catch (Http2Exception e) {
            String msg = sm.getString("upgradeHandler.invalidPreface", connectionId);
            if (log.isDebugEnabled()) {
                log.debug(msg, e);
            }
            throw new ProtocolException(msg);
        }
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.prefaceReceived", connectionId));
        }

        processConnection(webConnection, stream);
    }

    protected void processConnection(WebConnection webConnection, Stream stream) {
        // Send a ping to get an idea of round trip time as early as possible
        try {
            pingManager.sendPing(true);
        } catch (IOException ioe) {
            throw new ProtocolException(sm.getString("upgradeHandler.pingFailed", connectionId), ioe);
        }

        if (webConnection != null) {
            processStreamOnContainerThread(stream);
        }
    }

    protected Http2Parser getParser(String connectionId) {
        return new Http2Parser(connectionId, this, this);
    }


    protected void processStreamOnContainerThread(Stream stream) {
        StreamProcessor streamProcessor = new StreamProcessor(this, stream, adapter, socketWrapper);
        streamProcessor.setSslSupport(sslSupport);
        processStreamOnContainerThread(streamProcessor, SocketEvent.OPEN_READ);
    }


    void processStreamOnContainerThread(StreamProcessor streamProcessor, SocketEvent event) {
        StreamRunnable streamRunnable = new StreamRunnable(streamProcessor, event);
        if (streamConcurrency == null) {
            socketWrapper.execute(streamRunnable);
        } else {
            if (getStreamConcurrency() < protocol.getMaxConcurrentStreamExecution()) {
                increaseStreamConcurrency();
                socketWrapper.execute(streamRunnable);
            } else {
                queuedRunnable.offer(streamRunnable);
            }
        }
    }


    @Override
    public void setSocketWrapper(SocketWrapperBase<?> wrapper) {
        this.socketWrapper = wrapper;
    }


    @Override
    public void setSslSupport(SSLSupport sslSupport) {
        this.sslSupport = sslSupport;
    }


    @Override
    public SocketState upgradeDispatch(SocketEvent status) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.upgradeDispatch.entry", connectionId, status));
        }

        // WebConnection is not used so passing null here is fine
        // Might not be necessary. init() will handle that.
        init(null);


        SocketState result = SocketState.CLOSED;

        try {
            pingManager.sendPing(false);

            switch(status) {
            case OPEN_READ:
                try {
                    // There is data to read so use the read timeout while
                    // reading frames ...
                    socketWrapper.setReadTimeout(protocol.getReadTimeout());
                    // ... and disable the connection timeout
                    setConnectionTimeout(-1);
                    while (true) {
                        try {
                            if (!parser.readFrame(false)) {
                                break;
                            }
                        } catch (StreamException se) {
                            // Stream errors are not fatal to the connection so
                            // continue reading frames
                            Stream stream = getStream(se.getStreamId(), false);
                            if (stream == null) {
                                sendStreamReset(se);
                            } else {
                                stream.close(se);
                            }
                        }
                        if (overheadCount.get() > 0) {
                            throw new ConnectionException(
                                    sm.getString("upgradeHandler.tooMuchOverhead", connectionId),
                                    Http2Error.ENHANCE_YOUR_CALM);
                        }
                    }

                    // Need to know the correct timeout before starting the read
                    // but that may not be known at this time if one or more
                    // requests are currently being processed so don't set a
                    // timeout for the socket...
                    socketWrapper.setReadTimeout(-1);

                    // ...set a timeout on the connection
                    setConnectionTimeoutForStreamCount(activeRemoteStreamCount.get());

                } catch (Http2Exception ce) {
                    // Really ConnectionException
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("upgradeHandler.connectionError"), ce);
                    }
                    closeConnection(ce);
                    break;
                }

                if (connectionState.get() != ConnectionState.CLOSED) {
                    result = SocketState.UPGRADED;
                }
                break;

            case OPEN_WRITE:
                processWrites();

                result = SocketState.UPGRADED;
                break;

            case TIMEOUT:
                closeConnection(null);
                break;

            case DISCONNECT:
            case ERROR:
            case STOP:
            case CONNECT_FAIL:
                close();
                break;
            }
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.ioerror", connectionId), ioe);
            }
            close();
        }

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.upgradeDispatch.exit", connectionId, result));
        }
        return result;
    }


    /*
     * Sets the connection timeout based on the current number of active
     * streams.
     */
    protected void setConnectionTimeoutForStreamCount(int streamCount) {
        if (streamCount == 0) {
            // No streams currently active. Use the keep-alive
            // timeout for the connection.
            long keepAliveTimeout = protocol.getKeepAliveTimeout();
            if (keepAliveTimeout == -1) {
                setConnectionTimeout(-1);
            } else {
                setConnectionTimeout(System.currentTimeMillis() + keepAliveTimeout);
            }
        } else {
            // Streams currently active. Individual streams have
            // timeouts so keep the connection open.
            setConnectionTimeout(-1);
        }
    }


    private void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }


    @Override
    public void timeoutAsync(long now) {
        long connectionTimeout = this.connectionTimeout;
        if (now == -1 || connectionTimeout > -1 && now > connectionTimeout) {
            // Have to dispatch as this will be executed from a non-container
            // thread.
            socketWrapper.processSocket(SocketEvent.TIMEOUT, true);
        }
    }


    ConnectionSettingsRemote getRemoteSettings() {
        return remoteSettings;
    }


    ConnectionSettingsLocal getLocalSettings() {
        return localSettings;
    }


    Http2Protocol getProtocol() {
        return protocol;
    }


    @Override
    public void pause() {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.pause.entry", connectionId));
        }

        if (connectionState.compareAndSet(ConnectionState.CONNECTED, ConnectionState.PAUSING)) {
            pausedNanoTime = System.nanoTime();

            try {
                writeGoAwayFrame((1 << 31) - 1, Http2Error.NO_ERROR.getCode(), null);
            } catch (IOException ioe) {
                // This is fatal for the connection. Ignore it here. There will be
                // further attempts at I/O in upgradeDispatch() and it can better
                // handle the IO errors.
            }
        }
    }


    @Override
    public void destroy() {
        // NO-OP
    }


    void checkPauseState() throws IOException {
        if (connectionState.get() == ConnectionState.PAUSING) {
            if (pausedNanoTime + pingManager.getRoundTripTimeNano() < System.nanoTime()) {
                connectionState.compareAndSet(ConnectionState.PAUSING, ConnectionState.PAUSED);
                writeGoAwayFrame(maxProcessedStreamId, Http2Error.NO_ERROR.getCode(), null);
            }
        }
    }


    private int increaseStreamConcurrency() {
        return streamConcurrency.incrementAndGet();
    }

    private int decreaseStreamConcurrency() {
        return streamConcurrency.decrementAndGet();
    }

    private int getStreamConcurrency() {
        return streamConcurrency.get();
    }

    void executeQueuedStream() {
        if (streamConcurrency == null) {
            return;
        }
        decreaseStreamConcurrency();
        if (getStreamConcurrency() < protocol.getMaxConcurrentStreamExecution()) {
            StreamRunnable streamRunnable = queuedRunnable.poll();
            if (streamRunnable != null) {
                increaseStreamConcurrency();
                socketWrapper.execute(streamRunnable);
            }
        }
    }


    void sendStreamReset(StreamException se) throws IOException {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.rst.debug", connectionId,
                    Integer.toString(se.getStreamId()), se.getError(), se.getMessage()));
        }

        // Write a RST frame
        byte[] rstFrame = new byte[13];
        // Length
        ByteUtil.setThreeBytes(rstFrame, 0, 4);
        // Type
        rstFrame[3] = FrameType.RST.getIdByte();
        // No flags
        // Stream ID
        ByteUtil.set31Bits(rstFrame, 5, se.getStreamId());
        // Payload
        ByteUtil.setFourBytes(rstFrame, 9, se.getError().getCode());

        synchronized (socketWrapper) {
            socketWrapper.write(true, rstFrame, 0, rstFrame.length);
            socketWrapper.flush(true);
        }
    }


    void closeConnection(Http2Exception ce) {
        long code;
        byte[] msg;
        if (ce == null) {
            code = Http2Error.NO_ERROR.getCode();
            msg = null;
        } else {
            code = ce.getError().getCode();
            msg = ce.getMessage().getBytes(StandardCharsets.UTF_8);
        }
        try {
            writeGoAwayFrame(maxProcessedStreamId, code, msg);
        } catch (IOException ioe) {
            // Ignore. GOAWAY is sent on a best efforts basis and the original
            // error has already been logged.
        }
        close();
    }


    /**
     * Write the initial settings frame and any necessary supporting frames. If
     * the initial settings increase the initial window size, it will also be
     * necessary to send a WINDOW_UPDATE frame to increase the size of the flow
     * control window for the connection (stream 0).
     */
    protected void writeSettings() {
        // Send the initial settings frame
        try {
            byte[] settings = localSettings.getSettingsFrameForPending();
            socketWrapper.write(true, settings, 0, settings.length);
            byte[] windowUpdateFrame = createWindowUpdateForSettings();
            if (windowUpdateFrame.length > 0) {
                socketWrapper.write(true,  windowUpdateFrame, 0 , windowUpdateFrame.length);
            }
            socketWrapper.flush(true);
        } catch (IOException ioe) {
            String msg = sm.getString("upgradeHandler.sendPrefaceFail", connectionId);
            if (log.isDebugEnabled()) {
                log.debug(msg);
            }
            throw new ProtocolException(msg, ioe);
        }
    }


    /**
     * @return  The WINDOW_UPDATE frame if one is required or an empty array if
     *          no WINDOW_UPDATE is required.
     */
    protected byte[] createWindowUpdateForSettings() {
        // Build a WINDOW_UPDATE frame if one is required. If not, create an
        // empty byte array.
        byte[] windowUpdateFrame;
        int increment = protocol.getInitialWindowSize() - ConnectionSettingsBase.DEFAULT_INITIAL_WINDOW_SIZE;
        if (increment > 0) {
            // Build window update frame for stream 0
            windowUpdateFrame = new byte[13];
            ByteUtil.setThreeBytes(windowUpdateFrame, 0,  4);
            windowUpdateFrame[3] = FrameType.WINDOW_UPDATE.getIdByte();
            ByteUtil.set31Bits(windowUpdateFrame, 9, increment);
        } else {
            windowUpdateFrame = new byte[0];
        }

        return windowUpdateFrame;
    }


    protected void writeGoAwayFrame(int maxStreamId, long errorCode, byte[] debugMsg)
            throws IOException {
        byte[] fixedPayload = new byte[8];
        ByteUtil.set31Bits(fixedPayload, 0, maxStreamId);
        ByteUtil.setFourBytes(fixedPayload, 4, errorCode);
        int len = 8;
        if (debugMsg != null) {
            len += debugMsg.length;
        }
        byte[] payloadLength = new byte[3];
        ByteUtil.setThreeBytes(payloadLength, 0, len);

        synchronized (socketWrapper) {
            socketWrapper.write(true, payloadLength, 0, payloadLength.length);
            socketWrapper.write(true, GOAWAY, 0, GOAWAY.length);
            socketWrapper.write(true, fixedPayload, 0, 8);
            if (debugMsg != null) {
                socketWrapper.write(true, debugMsg, 0, debugMsg.length);
            }
            socketWrapper.flush(true);
        }
    }

    void writeHeaders(Stream stream, int pushedStreamId, MimeHeaders mimeHeaders,
            boolean endOfStream, int payloadSize) throws IOException {
        // This ensures the Stream processing thread has control of the socket.
        synchronized (socketWrapper) {
            doWriteHeaders(stream, pushedStreamId, mimeHeaders, endOfStream, payloadSize);
        }
        stream.sentHeaders();
        if (endOfStream) {
            stream.sentEndOfStream();
        }
    }


    /*
     * Separate method to allow Http2AsyncUpgradeHandler to call this code
     * without synchronizing on socketWrapper since it doesn't need to.
     */
    protected HeaderFrameBuffers doWriteHeaders(Stream stream, int pushedStreamId,
            MimeHeaders mimeHeaders, boolean endOfStream, int payloadSize) throws IOException {

        if (log.isDebugEnabled()) {
            if (pushedStreamId == 0) {
                log.debug(sm.getString("upgradeHandler.writeHeaders", connectionId,
                        stream.getIdentifier()));
            } else {
                log.debug(sm.getString("upgradeHandler.writePushHeaders", connectionId,
                        stream.getIdentifier(), Integer.valueOf(pushedStreamId),
                        Boolean.valueOf(endOfStream)));
            }
        }

        if (!stream.canWrite()) {
            return null;
        }

        HeaderFrameBuffers headerFrameBuffers = getHeaderFrameBuffers(payloadSize);

        byte[] pushedStreamIdBytes = null;
        if (pushedStreamId > 0) {
            pushedStreamIdBytes = new byte[4];
            ByteUtil.set31Bits(pushedStreamIdBytes, 0, pushedStreamId);
        }

        boolean first = true;
        State state = null;

        while (state != State.COMPLETE) {
            headerFrameBuffers.startFrame();
            if (first && pushedStreamIdBytes != null) {
                headerFrameBuffers.getPayload().put(pushedStreamIdBytes);
            }
            state = getHpackEncoder().encode(mimeHeaders, headerFrameBuffers.getPayload());
            headerFrameBuffers.getPayload().flip();
            if (state == State.COMPLETE || headerFrameBuffers.getPayload().limit() > 0) {
                ByteUtil.setThreeBytes(headerFrameBuffers.getHeader(), 0, headerFrameBuffers.getPayload().limit());
                if (first) {
                    first = false;
                    if (pushedStreamIdBytes == null) {
                        headerFrameBuffers.getHeader()[3] = FrameType.HEADERS.getIdByte();
                    } else {
                        headerFrameBuffers.getHeader()[3] = FrameType.PUSH_PROMISE.getIdByte();
                    }
                    if (endOfStream) {
                        headerFrameBuffers.getHeader()[4] = FLAG_END_OF_STREAM;
                    }
                } else {
                    headerFrameBuffers.getHeader()[3] = FrameType.CONTINUATION.getIdByte();
                }
                if (state == State.COMPLETE) {
                    headerFrameBuffers.getHeader()[4] += FLAG_END_OF_HEADERS;
                }
                if (log.isDebugEnabled()) {
                    log.debug(headerFrameBuffers.getPayload().limit() + " bytes");
                }
                ByteUtil.set31Bits(headerFrameBuffers.getHeader(), 5, stream.getIdAsInt());
                headerFrameBuffers.endFrame();
            } else if (state == State.UNDERFLOW) {
                headerFrameBuffers.expandPayload();
            }
        }
        headerFrameBuffers.endHeaders();
        return headerFrameBuffers;
    }

    protected HeaderFrameBuffers getHeaderFrameBuffers(int initialPayloadSize) {
        return new DefaultHeaderFrameBuffers(initialPayloadSize);
    }


    protected HpackEncoder getHpackEncoder() {
        if (hpackEncoder == null) {
            hpackEncoder = new HpackEncoder();
        }
        // Ensure latest agreed table size is used
        hpackEncoder.setMaxTableSize(remoteSettings.getHeaderTableSize());
        return hpackEncoder;
    }


    void writeBody(Stream stream, ByteBuffer data, int len, boolean finished) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.writeBody", connectionId, stream.getIdentifier(),
                    Integer.toString(len)));
        }

        reduceOverheadCount();

        // Need to check this now since sending end of stream will change this.
        boolean writeable = stream.canWrite();
        byte[] header = new byte[9];
        ByteUtil.setThreeBytes(header, 0, len);
        header[3] = FrameType.DATA.getIdByte();
        if (finished) {
            header[4] = FLAG_END_OF_STREAM;
            stream.sentEndOfStream();
            if (!stream.isActive()) {
                setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
            }
        }
        if (writeable) {
            ByteUtil.set31Bits(header, 5, stream.getIdAsInt());
            synchronized (socketWrapper) {
                try {
                    socketWrapper.write(true, header, 0, header.length);
                    int orgLimit = data.limit();
                    data.limit(data.position() + len);
                    socketWrapper.write(true, data);
                    data.limit(orgLimit);
                    socketWrapper.flush(true);
                } catch (IOException ioe) {
                    handleAppInitiatedIOException(ioe);
                }
            }
        }
    }


    /*
     * Handles an I/O error on the socket underlying the HTTP/2 connection when
     * it is triggered by application code (usually reading the request or
     * writing the response). Such I/O errors are fatal so the connection is
     * closed. The exception is re-thrown to make the client code aware of the
     * problem.
     *
     * Note: We can not rely on this exception reaching the socket processor
     *       since the application code may swallow it.
     */
    protected void handleAppInitiatedIOException(IOException ioe) throws IOException {
        close();
        throw ioe;
    }


    /*
     * Needs to know if this was application initiated since that affects the
     * error handling.
     */
    void writeWindowUpdate(Stream stream, int increment, boolean applicationInitiated)
            throws IOException {
        if (!stream.canWrite()) {
            return;
        }
        synchronized (socketWrapper) {
            // Build window update frame for stream 0
            byte[] frame = new byte[13];
            ByteUtil.setThreeBytes(frame, 0,  4);
            frame[3] = FrameType.WINDOW_UPDATE.getIdByte();
            ByteUtil.set31Bits(frame, 9, increment);
            socketWrapper.write(true, frame, 0, frame.length);
            // Change stream Id and re-use
            ByteUtil.set31Bits(frame, 5, stream.getIdAsInt());
            try {
                socketWrapper.write(true, frame, 0, frame.length);
                socketWrapper.flush(true);
            } catch (IOException ioe) {
                if (applicationInitiated) {
                    handleAppInitiatedIOException(ioe);
                } else {
                    throw ioe;
                }
            }
        }
    }


    protected void processWrites() throws IOException {
        synchronized (socketWrapper) {
            if (socketWrapper.flush(false)) {
                socketWrapper.registerWriteInterest();
            }
        }
    }


    int reserveWindowSize(Stream stream, int reservation, boolean block) throws IOException {
        // Need to be holding the stream lock so releaseBacklog() can't notify
        // this thread until after this thread enters wait()
        int allocation = 0;
        synchronized (stream) {
            do {
                synchronized (this) {
                    if (!stream.canWrite()) {
                        throw new CloseNowException(
                                sm.getString("upgradeHandler.stream.notWritable",
                                        stream.getConnectionId(), stream.getIdentifier()));
                    }
                    long windowSize = getWindowSize();
                    if (windowSize < 1 || backLogSize > 0) {
                        // Has this stream been granted an allocation
                        BacklogTracker tracker = backLogStreams.get(stream);
                        if (tracker == null) {
                            tracker = new BacklogTracker(reservation);
                            backLogStreams.put(stream, tracker);
                            backLogSize += reservation;
                            // Add the parents as well
                            AbstractStream parent = stream.getParentStream();
                            while (parent != null && backLogStreams.putIfAbsent(parent, new BacklogTracker()) == null) {
                                parent = parent.getParentStream();
                            }
                        } else {
                            if (tracker.getUnusedAllocation() > 0) {
                                allocation = tracker.getUnusedAllocation();
                                decrementWindowSize(allocation);
                                if (tracker.getRemainingReservation() == 0) {
                                    // The reservation has been fully allocated
                                    // so this stream can be removed from the
                                    // backlog.
                                    backLogStreams.remove(stream);
                                } else {
                                    // This allocation has been used. Leave the
                                    // stream on the backlog as it still has
                                    // more bytes to write.
                                    tracker.useAllocation();
                                }
                            }
                        }
                    } else if (windowSize < reservation) {
                        allocation = (int) windowSize;
                        decrementWindowSize(allocation);
                    } else {
                        allocation = reservation;
                        decrementWindowSize(allocation);
                    }
                }
                if (allocation == 0) {
                    if (block) {
                        try {
                            // Connection level window is empty. Although this
                            // request is for a stream, use the connection
                            // timeout
                            long writeTimeout = protocol.getWriteTimeout();
                            stream.waitForConnectionAllocation(writeTimeout);
                            // Has this stream been granted an allocation
                            // Note: If the stream in not in this Map then the
                            //       requested write has been fully allocated
                            BacklogTracker tracker;
                            // Ensure allocations made in other threads are visible
                            synchronized (this) {
                                tracker = backLogStreams.get(stream);
                            }
                            if (tracker != null && tracker.getUnusedAllocation() == 0) {
                                if (log.isDebugEnabled()) {
                                    log.debug(sm.getString("upgradeHandler.noAllocation",
                                            connectionId, stream.getIdentifier()));
                                }
                                // No allocation
                                // Close the connection. Do this first since
                                // closing the stream will raise an exception
                                close();
                                // Close the stream (in app code so need to
                                // signal to app stream is closing)
                                stream.doWriteTimeout();
                            }
                        } catch (InterruptedException e) {
                            throw new IOException(sm.getString(
                                    "upgradeHandler.windowSizeReservationInterrupted", connectionId,
                                    stream.getIdentifier(), Integer.toString(reservation)), e);
                        }
                    } else {
                        stream.waitForConnectionAllocationNonBlocking();
                        return 0;
                    }
                }
            } while (allocation == 0);
        }
        return allocation;
    }



    @SuppressWarnings("sync-override") // notify() needs to be outside sync
                                       // to avoid deadlock
    @Override
    protected void incrementWindowSize(int increment) throws Http2Exception {
        Set<AbstractStream> streamsToNotify = null;

        synchronized (this) {
            long windowSize = getWindowSize();
            if (windowSize < 1 && windowSize + increment > 0) {
                streamsToNotify = releaseBackLog((int) (windowSize +increment));
            }
            super.incrementWindowSize(increment);
        }

        if (streamsToNotify != null) {
            for (AbstractStream stream : streamsToNotify) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("upgradeHandler.releaseBacklog",
                            connectionId, stream.getIdentifier()));
                }
                // There is never any O/P on stream zero but it is included in
                // the backlog as it simplifies the code. Skip it if it appears
                // here.
                if (this == stream) {
                    continue;
                }
                ((Stream) stream).notifyConnection();
            }
        }
    }


    /**
     * Process send file (if supported) for the given stream. The appropriate
     * request attributes should be set before calling this method.
     *
     * @param sendfileData  The stream and associated data to process
     *
     * @return  The result of the send file processing
     */
    protected SendfileState processSendfile(SendfileData sendfileData) {
        return SendfileState.DONE;
    }


    private synchronized Set<AbstractStream> releaseBackLog(int increment) {
        Set<AbstractStream> result = new HashSet<>();
        if (backLogSize < increment) {
            // Can clear the whole backlog
            result.addAll(backLogStreams.keySet());
            backLogStreams.clear();
            backLogSize = 0;
        } else {
            int leftToAllocate = increment;
            while (leftToAllocate > 0) {
                leftToAllocate = allocate(this, leftToAllocate);
            }
            for (Entry<AbstractStream,BacklogTracker> entry : backLogStreams.entrySet()) {
                int allocation = entry.getValue().getUnusedAllocation();
                if (allocation > 0) {
                    backLogSize -= allocation;
                    if (!entry.getValue().isNotifyInProgress()) {
                        result.add(entry.getKey());
                        entry.getValue().startNotify();
                    }
                }
            }
        }
        return result;
    }


    private int allocate(AbstractStream stream, int allocation) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.allocate.debug", getConnectionId(),
                    stream.getIdentifier(), Integer.toString(allocation)));
        }
        // Allocate to the specified stream
        BacklogTracker tracker = backLogStreams.get(stream);

        int leftToAllocate = tracker.allocate(allocation);

        if (leftToAllocate == 0) {
            return 0;
        }

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.allocate.left",
                    getConnectionId(), stream.getIdentifier(), Integer.toString(leftToAllocate)));
        }

        // Recipients are children of the current stream that are in the
        // backlog.
        Set<AbstractStream> recipients = new HashSet<>();
        recipients.addAll(stream.getChildStreams());
        recipients.retainAll(backLogStreams.keySet());

        // Loop until we run out of allocation or recipients
        while (leftToAllocate > 0) {
            if (recipients.size() == 0) {
                backLogStreams.remove(stream);
                return leftToAllocate;
            }

            int totalWeight = 0;
            for (AbstractStream recipient : recipients) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("upgradeHandler.allocate.recipient",
                            getConnectionId(), stream.getIdentifier(), recipient.getIdentifier(),
                            Integer.toString(recipient.getWeight())));
                }
                totalWeight += recipient.getWeight();
            }

            // Use an Iterator so fully allocated children/recipients can be
            // removed.
            Iterator<AbstractStream> iter = recipients.iterator();
            int allocated = 0;
            while (iter.hasNext()) {
                AbstractStream recipient = iter.next();
                int share = leftToAllocate * recipient.getWeight() / totalWeight;
                if (share == 0) {
                    // This is to avoid rounding issues triggering an infinite
                    // loop. It will cause a very slight over allocation but
                    // HTTP/2 should cope with that.
                    share = 1;
                }
                int remainder = allocate(recipient, share);
                // Remove recipients that receive their full allocation so that
                // they are excluded from the next allocation round.
                if (remainder > 0) {
                    iter.remove();
                }
                allocated += (share - remainder);
            }
            leftToAllocate -= allocated;
        }

        return 0;
    }


    private Stream getStream(int streamId, boolean unknownIsError) throws ConnectionException {
        Integer key = Integer.valueOf(streamId);
        Stream result = streams.get(key);
        if (result == null && unknownIsError) {
            // Stream has been closed and removed from the map
            throw new ConnectionException(sm.getString("upgradeHandler.stream.closed", key),
                    Http2Error.PROTOCOL_ERROR);
        }
        return result;
    }


    private Stream createRemoteStream(int streamId) throws ConnectionException {
        Integer key = Integer.valueOf(streamId);

        if (streamId %2 != 1) {
            throw new ConnectionException(
                    sm.getString("upgradeHandler.stream.even", key), Http2Error.PROTOCOL_ERROR);
        }

        pruneClosedStreams(streamId);

        Stream result = new Stream(key, this);
        streams.put(key, result);
        return result;
    }


    private Stream createLocalStream(Request request) {
        int streamId = nextLocalStreamId.getAndAdd(2);

        Integer key = Integer.valueOf(streamId);

        Stream result = new Stream(key, this, request);
        streams.put(key, result);
        return result;
    }


    private void close() {
        ConnectionState previous = connectionState.getAndSet(ConnectionState.CLOSED);
        if (previous == ConnectionState.CLOSED) {
            // Already closed
            return;
        }

        for (Stream stream : streams.values()) {
            // The connection is closing. Close the associated streams as no
            // longer required (also notifies any threads waiting for allocations).
            stream.receiveReset(Http2Error.CANCEL.getCode());
        }
        try {
            socketWrapper.close();
        } catch (Exception e) {
            log.debug(sm.getString("upgradeHandler.socketCloseFailed"), e);
        }
    }


    private void pruneClosedStreams(int streamId) {
        // Only prune every 10 new streams
        if (newStreamsSinceLastPrune < 9) {
            // Not atomic. Increments may be lost. Not a problem.
            newStreamsSinceLastPrune++;
            return;
        }
        // Reset counter
        newStreamsSinceLastPrune = 0;

        // RFC 7540, 5.3.4 endpoints should maintain state for at least the
        // maximum number of concurrent streams
        long max = localSettings.getMaxConcurrentStreams();

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.pruneStart", connectionId,
                    Long.toString(max), Integer.toString(streams.size())));
        }

        // Allow an additional 10% for closed streams that are used in the
        // priority tree
        max = max + max / 10;
        if (max > Integer.MAX_VALUE) {
            max = Integer.MAX_VALUE;
        }

        int toClose = streams.size() - (int) max;
        if (toClose < 1) {
            return;
        }

        // Need to try and close some streams.
        // Try to close streams in this order
        // 1. Completed streams used for a request with no children
        // 2. Completed streams used for a request with children
        // 3. Closed final streams
        //
        // Steps 1 and 2 will always be completed.
        // Step 3 will be completed to the minimum extent necessary to bring the
        // total number of streams under the limit.

        // Use these sets to track the different classes of streams
        TreeSet<Integer> candidatesStepOne = new TreeSet<>();
        TreeSet<Integer> candidatesStepTwo = new TreeSet<>();
        TreeSet<Integer> candidatesStepThree = new TreeSet<>();

        for (Entry<Integer, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            // Never remove active streams
            if (stream.isActive()) {
                continue;
            }

            if (stream.isClosedFinal()) {
                // This stream went from IDLE to CLOSED and is likely to have
                // been created by the client as part of the priority tree.
                candidatesStepThree.add(entry.getKey());
            } else if (stream.getChildStreams().size() == 0) {
                // Closed, no children
                candidatesStepOne.add(entry.getKey());
            } else {
                // Closed, with children
                candidatesStepTwo.add(entry.getKey());
            }
        }

        // Process the step one list
        for (Integer streamIdToRemove : candidatesStepOne) {
            // Remove this childless stream
            Stream removedStream = streams.remove(streamIdToRemove);
            removedStream.detachFromParent();
            toClose--;
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.pruned", connectionId, streamIdToRemove));
            }

            // Did this make the parent childless?
            AbstractStream parent = removedStream.getParentStream();
            while (parent instanceof Stream && !((Stream) parent).isActive() &&
                    !((Stream) parent).isClosedFinal() && parent.getChildStreams().size() == 0) {
                streams.remove(parent.getIdentifier());
                parent.detachFromParent();
                toClose--;
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("upgradeHandler.pruned", connectionId, streamIdToRemove));
                }
                // Also need to remove this stream from the p2 list
                candidatesStepTwo.remove(parent.getIdentifier());
                parent = parent.getParentStream();
            }
        }

        // Process the P2 list
        for (Integer streamIdToRemove : candidatesStepTwo) {
            removeStreamFromPriorityTree(streamIdToRemove);
            toClose--;
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.pruned", connectionId, streamIdToRemove));
            }
        }

        while (toClose > 0 && candidatesStepThree.size() > 0) {
            Integer streamIdToRemove = candidatesStepThree.pollLast();
            removeStreamFromPriorityTree(streamIdToRemove);
            toClose--;
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.prunedPriority", connectionId, streamIdToRemove));
            }
        }

        if (toClose > 0) {
            log.warn(sm.getString("upgradeHandler.pruneIncomplete", connectionId,
                    Integer.toString(streamId), Integer.toString(toClose)));
        }
    }


    private void removeStreamFromPriorityTree(Integer streamIdToRemove) {
        Stream streamToRemove = streams.remove(streamIdToRemove);
        // Move the removed Stream's children to the removed Stream's
        // parent.
        Set<Stream> children = streamToRemove.getChildStreams();
        if (streamToRemove.getChildStreams().size() == 1) {
            // Shortcut
            streamToRemove.getChildStreams().iterator().next().rePrioritise(
                    streamToRemove.getParentStream(), streamToRemove.getWeight());
        } else {
            int totalWeight = 0;
            for (Stream child : children) {
                totalWeight += child.getWeight();
            }
            for (Stream child : children) {
                streamToRemove.getChildStreams().iterator().next().rePrioritise(
                        streamToRemove.getParentStream(),
                        streamToRemove.getWeight() * child.getWeight() / totalWeight);
            }
        }
        streamToRemove.detachFromParent();
        streamToRemove.getChildStreams().clear();
    }


    void push(Request request, Stream associatedStream) throws IOException {
        if (localSettings.getMaxConcurrentStreams() < activeRemoteStreamCount.incrementAndGet()) {
            // If there are too many open streams, simply ignore the push
            // request.
            setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
            return;
        }

        Stream pushStream;

        // Synchronized since PUSH_PROMISE frames have to be sent in order. Once
        // the stream has been created we need to ensure that the PUSH_PROMISE
        // is sent before the next stream is created for a PUSH_PROMISE.
        synchronized (socketWrapper) {
            pushStream = createLocalStream(request);
            writeHeaders(associatedStream, pushStream.getIdAsInt(), request.getMimeHeaders(),
                    false, Constants.DEFAULT_HEADERS_FRAME_SIZE);
        }

        pushStream.sentPushPromise();

        processStreamOnContainerThread(pushStream);
    }


    @Override
    protected final String getConnectionId() {
        return connectionId;
    }


    @Override
    protected final int getWeight() {
        return 0;
    }


    private void reduceOverheadCount() {
        overheadCount.decrementAndGet();
    }


    private void increaseOverheadCount() {
        overheadCount.addAndGet(getProtocol().getOverheadCountFactor());
    }


    // ----------------------------------------------- Http2Parser.Input methods

    @Override
    public boolean fill(boolean block, byte[] data, int offset, int length) throws IOException {
        int len = length;
        int pos = offset;
        boolean nextReadBlock = block;
        int thisRead = 0;

        while (len > 0) {
            thisRead = socketWrapper.read(nextReadBlock, data, pos, len);
            if (thisRead == 0) {
                if (nextReadBlock) {
                    // Should never happen
                    throw new IllegalStateException();
                } else {
                    return false;
                }
            } else if (thisRead == -1) {
                if (connectionState.get().isNewStreamAllowed()) {
                    throw new EOFException();
                } else {
                    return false;
                }
            } else {
                pos += thisRead;
                len -= thisRead;
                nextReadBlock = true;
            }
        }

        return true;
    }


    @Override
    public int getMaxFrameSize() {
        return localSettings.getMaxFrameSize();
    }


    // ---------------------------------------------- Http2Parser.Output methods

    @Override
    public HpackDecoder getHpackDecoder() {
        if (hpackDecoder == null) {
            hpackDecoder = new HpackDecoder(localSettings.getHeaderTableSize());
        }
        return hpackDecoder;
    }


    @Override
    public ByteBuffer startRequestBodyFrame(int streamId, int payloadSize, boolean endOfStream) throws Http2Exception {
        // DATA frames reduce the overhead count ...
        reduceOverheadCount();

        // .. but lots of small payloads are inefficient so that will increase
        // the overhead count unless it is the final DATA frame where small
        // payloads are expected.

        // See also https://bz.apache.org/bugzilla/show_bug.cgi?id=63690
        // The buffering behaviour of some clients means that small data frames
        // are much more frequent (roughly 1 in 20) than expected. Use an
        // average over two frames to avoid false positives.
        if (!endOfStream) {
            int overheadThreshold = protocol.getOverheadDataThreshold();
            int average = (lastNonFinalDataPayload >> 1) + (payloadSize >> 1);
            lastNonFinalDataPayload = payloadSize;
            // Avoid division by zero
            if (average == 0) {
                average = 1;
            }
            if (average < overheadThreshold) {
                overheadCount.addAndGet(overheadThreshold / average);
            }
        }

        Stream stream = getStream(streamId, true);
        stream.checkState(FrameType.DATA);
        stream.receivedData(payloadSize);
        return stream.getInputByteBuffer();
    }


    @Override
    public void endRequestBodyFrame(int streamId) throws Http2Exception {
        Stream stream = getStream(streamId, true);
        stream.getInputBuffer().onDataAvailable();
    }


    @Override
    public void receivedEndOfStream(int streamId) throws ConnectionException {
        Stream stream = getStream(streamId, connectionState.get().isNewStreamAllowed());
        if (stream != null) {
            stream.receivedEndOfStream();
            if (!stream.isActive()) {
                setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
            }
        }
    }


    @Override
    public void swallowedPadding(int streamId, int paddingLength) throws
            ConnectionException, IOException {
        Stream stream = getStream(streamId, true);
        // +1 is for the payload byte used to define the padding length
        writeWindowUpdate(stream, paddingLength + 1, false);
    }


    @Override
    public HeaderEmitter headersStart(int streamId, boolean headersEndStream)
            throws Http2Exception, IOException {

        // Check the pause state before processing headers since the pause state
        // determines if a new stream is created or if this stream is ignored.
        checkPauseState();

        if (connectionState.get().isNewStreamAllowed()) {
            Stream stream = getStream(streamId, false);
            if (stream == null) {
                stream = createRemoteStream(streamId);
            }
            if (streamId < maxActiveRemoteStreamId) {
                throw new ConnectionException(sm.getString("upgradeHandler.stream.old",
                        Integer.valueOf(streamId), Integer.valueOf(maxActiveRemoteStreamId)),
                        Http2Error.PROTOCOL_ERROR);
            }
            stream.checkState(FrameType.HEADERS);
            stream.receivedStartOfHeaders(headersEndStream);
            closeIdleStreams(streamId);
            if (localSettings.getMaxConcurrentStreams() < activeRemoteStreamCount.incrementAndGet()) {
                setConnectionTimeoutForStreamCount(activeRemoteStreamCount.decrementAndGet());
                // Ignoring maxConcurrentStreams increases the overhead count
                increaseOverheadCount();
                throw new StreamException(sm.getString("upgradeHandler.tooManyRemoteStreams",
                        Long.toString(localSettings.getMaxConcurrentStreams())),
                        Http2Error.REFUSED_STREAM, streamId);
            }
            // Valid new stream reduces the overhead count
            reduceOverheadCount();
            return stream;
        } else {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("upgradeHandler.noNewStreams",
                        connectionId, Integer.toString(streamId)));
            }
            reduceOverheadCount();
            // Stateless so a static can be used to save on GC
            return HEADER_SINK;
        }
    }


    private void closeIdleStreams(int newMaxActiveRemoteStreamId) throws Http2Exception {
        for (int i = maxActiveRemoteStreamId + 2; i < newMaxActiveRemoteStreamId; i += 2) {
            Stream stream = getStream(i, false);
            if (stream != null) {
                stream.closeIfIdle();
            }
        }
        maxActiveRemoteStreamId = newMaxActiveRemoteStreamId;
    }


    @Override
    public void reprioritise(int streamId, int parentStreamId,
            boolean exclusive, int weight) throws Http2Exception {
        if (streamId == parentStreamId) {
            throw new ConnectionException(sm.getString("upgradeHandler.dependency.invalid",
                    getConnectionId(), Integer.valueOf(streamId)), Http2Error.PROTOCOL_ERROR);
        }

        increaseOverheadCount();

        Stream stream = getStream(streamId, false);
        if (stream == null) {
            stream = createRemoteStream(streamId);
        }
        stream.checkState(FrameType.PRIORITY);
        AbstractStream parentStream = getStream(parentStreamId, false);
        if (parentStream == null) {
            parentStream = this;
        }
        stream.rePrioritise(parentStream, exclusive, weight);
    }


    @Override
    public void headersContinue(int payloadSize, boolean endOfHeaders) {
        // Generally, continuation frames don't impact the overhead count but if
        // they are small and the frame isn't the final header frame then that
        // is indicative of an abusive client
        if (!endOfHeaders) {
            int overheadThreshold = getProtocol().getOverheadContinuationThreshold();
            if (payloadSize < overheadThreshold) {
                if (payloadSize == 0) {
                    // Avoid division by zero
                    overheadCount.addAndGet(overheadThreshold);
                } else {
                    overheadCount.addAndGet(overheadThreshold / payloadSize);
                }
            }
        }
    }


    @Override
    public void headersEnd(int streamId) throws ConnectionException {
        Stream stream = getStream(streamId, connectionState.get().isNewStreamAllowed());
        if (stream != null) {
            setMaxProcessedStream(streamId);
            if (stream.isActive()) {
                if (stream.receivedEndOfHeaders()) {
                    processStreamOnContainerThread(stream);
                }
            }
        }
    }


    private void setMaxProcessedStream(int streamId) {
        if (maxProcessedStreamId < streamId) {
            maxProcessedStreamId = streamId;
        }
    }


    @Override
    public void reset(int streamId, long errorCode) throws Http2Exception  {
        Stream stream = getStream(streamId, true);
        stream.checkState(FrameType.RST);
        stream.receiveReset(errorCode);
    }


    @Override
    public void setting(Setting setting, long value) throws ConnectionException {

        increaseOverheadCount();

        // Possible with empty settings frame
        if (setting == null) {
            return;
        }

        // Special handling required
        if (setting == Setting.INITIAL_WINDOW_SIZE) {
            long oldValue = remoteSettings.getInitialWindowSize();
            // Do this first in case new value is invalid
            remoteSettings.set(setting, value);
            int diff = (int) (value - oldValue);
            for (Stream stream : streams.values()) {
                try {
                    stream.incrementWindowSize(diff);
                } catch (Http2Exception h2e) {
                    stream.close(new StreamException(sm.getString(
                            "upgradeHandler.windowSizeTooBig", connectionId,
                            stream.getIdentifier()),
                            h2e.getError(), stream.getIdAsInt()));
               }
            }
        } else {
            remoteSettings.set(setting, value);
        }
    }


    @Override
    public void settingsEnd(boolean ack) throws IOException {
        if (ack) {
            if (!localSettings.ack()) {
                // Ack was unexpected
                log.warn(sm.getString(
                        "upgradeHandler.unexpectedAck", connectionId, getIdentifier()));
            }
        } else {
            synchronized (socketWrapper) {
                socketWrapper.write(true, SETTINGS_ACK, 0, SETTINGS_ACK.length);
                socketWrapper.flush(true);
            }
        }
    }


    @Override
    public void pingReceive(byte[] payload, boolean ack) throws IOException {
        if (!ack) {
            increaseOverheadCount();
        }
        pingManager.receivePing(payload, ack);
    }


    @Override
    public void goaway(int lastStreamId, long errorCode, String debugData) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("upgradeHandler.goaway.debug", connectionId,
                    Integer.toString(lastStreamId), Long.toHexString(errorCode), debugData));
        }
        close();
    }


    @Override
    public void incrementWindowSize(int streamId, int increment) throws Http2Exception {
        // See also https://bz.apache.org/bugzilla/show_bug.cgi?id=63690
        // The buffering behaviour of some clients means that small data frames
        // are much more frequent (roughly 1 in 20) than expected. Some clients
        // issue a Window update for every DATA frame so a similar pattern may
        // be observed. Use an average over two frames to avoid false positives.

        int average = (lastWindowUpdate >> 1) + (increment >> 1);
        int overheadThreshold = protocol.getOverheadWindowUpdateThreshold();
        lastWindowUpdate = increment;
        // Avoid division by zero
        if (average == 0) {
            average = 1;
        }

        if (streamId == 0) {
            // Check for small increments which are inefficient
            if (average < overheadThreshold) {
                // The smaller the increment, the larger the overhead
                overheadCount.addAndGet(overheadThreshold / average);
            }

            incrementWindowSize(increment);
        } else {
            Stream stream = getStream(streamId, true);

            // Check for small increments which are inefficient
            if (average < overheadThreshold) {
                // For Streams, client might only release the minimum so check
                // against current demand
                BacklogTracker tracker = backLogStreams.get(stream);
                if (tracker == null || increment < tracker.getRemainingReservation()) {
                    // The smaller the increment, the larger the overhead
                    overheadCount.addAndGet(overheadThreshold / average);
                }
            }

            stream.checkState(FrameType.WINDOW_UPDATE);
            stream.incrementWindowSize(increment);
        }
    }


    @Override
    public void swallowed(int streamId, FrameType frameType, int flags, int size)
            throws IOException {
        // NO-OP.
    }


    protected class PingManager {

        protected boolean initiateDisabled = false;

        // 10 seconds
        protected final long pingIntervalNano = 10000000000L;

        protected int sequence = 0;
        protected long lastPingNanoTime = Long.MIN_VALUE;

        protected Queue<PingRecord> inflightPings = new ConcurrentLinkedQueue<>();
        protected Queue<Long> roundTripTimes = new ConcurrentLinkedQueue<>();

        /**
         * Check to see if a ping was sent recently and, if not, send one.
         *
         * @param force Send a ping, even if one was sent recently
         *
         * @throws IOException If an I/O issue prevents the ping from being sent
         */
        public void sendPing(boolean force) throws IOException {
            if (initiateDisabled) {
                return;
            }
            long now = System.nanoTime();
            if (force || now - lastPingNanoTime > pingIntervalNano) {
                lastPingNanoTime = now;
                byte[] payload = new byte[8];
                synchronized (socketWrapper) {
                    int sentSequence = ++sequence;
                    PingRecord pingRecord = new PingRecord(sentSequence, now);
                    inflightPings.add(pingRecord);
                    ByteUtil.set31Bits(payload, 4, sentSequence);
                    socketWrapper.write(true, PING, 0, PING.length);
                    socketWrapper.write(true, payload, 0, payload.length);
                    socketWrapper.flush(true);
                }
            }
        }

        public void receivePing(byte[] payload, boolean ack) throws IOException {
            if (ack) {
                // Extract the sequence from the payload
                int receivedSequence = ByteUtil.get31Bits(payload, 4);
                PingRecord pingRecord = inflightPings.poll();
                while (pingRecord != null && pingRecord.getSequence() < receivedSequence) {
                    pingRecord = inflightPings.poll();
                }
                if (pingRecord == null) {
                    // Unexpected ACK. Log it.
                } else {
                    long roundTripTime = System.nanoTime() - pingRecord.getSentNanoTime();
                    roundTripTimes.add(Long.valueOf(roundTripTime));
                    while (roundTripTimes.size() > 3) {
                        // Ignore the returned value as we just want to reduce
                        // the queue to 3 entries to use for the rolling average.
                        roundTripTimes.poll();
                    }
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("pingManager.roundTripTime",
                                connectionId, Long.valueOf(roundTripTime)));
                    }
                }

            } else {
                // Client originated ping. Echo it back.
                synchronized (socketWrapper) {
                    socketWrapper.write(true, PING_ACK, 0, PING_ACK.length);
                    socketWrapper.write(true, payload, 0, payload.length);
                    socketWrapper.flush(true);
                }
            }
        }

        public long getRoundTripTimeNano() {
            return (long) roundTripTimes.stream().mapToLong(x -> x.longValue()).average().orElse(0);
        }
    }


    protected static class PingRecord {

        private final int sequence;
        private final long sentNanoTime;

        public PingRecord(int sequence, long sentNanoTime) {
            this.sequence = sequence;
            this.sentNanoTime = sentNanoTime;
        }

        public int getSequence() {
            return sequence;
        }

        public long getSentNanoTime() {
            return sentNanoTime;
        }
    }


    private enum ConnectionState {

        NEW(true),
        CONNECTED(true),
        PAUSING(true),
        PAUSED(false),
        CLOSED(false);

        private final boolean newStreamsAllowed;

        private ConnectionState(boolean newStreamsAllowed) {
            this.newStreamsAllowed = newStreamsAllowed;
        }

        public boolean isNewStreamAllowed() {
            return newStreamsAllowed;
        }
    }


    protected static interface HeaderFrameBuffers {
        public void startFrame();
        public void endFrame() throws IOException;
        public void endHeaders() throws IOException;
        public byte[] getHeader();
        public ByteBuffer getPayload();
        public void expandPayload();
    }


    private class DefaultHeaderFrameBuffers implements HeaderFrameBuffers {

        private final byte[] header;
        private ByteBuffer payload;

        public DefaultHeaderFrameBuffers(int initialPayloadSize) {
            header = new byte[9];
            payload = ByteBuffer.allocate(initialPayloadSize);
        }

        @Override
        public void startFrame() {
            // NO-OP
        }


        @Override
        public void endFrame() throws IOException {
            try {
                socketWrapper.write(true, header, 0, header.length);
                socketWrapper.write(true, payload);
                socketWrapper.flush(true);
            } catch (IOException ioe) {
                handleAppInitiatedIOException(ioe);
            }
            payload.clear();
        }

        @Override
        public void endHeaders() {
            // NO-OP
        }

        @Override
        public byte[] getHeader() {
            return header;
        }

        @Override
        public ByteBuffer getPayload() {
            return payload;
        }

        @Override
        public void expandPayload() {
            payload = ByteBuffer.allocate(payload.capacity() * 2);
        }
    }


    private static class BacklogTracker {

        private int remainingReservation;
        private int unusedAllocation;
        private boolean notifyInProgress;

        public BacklogTracker() {
        }

        public BacklogTracker(int reservation) {
            remainingReservation = reservation;
        }

        /**
         * @return The number of bytes requiring an allocation from the
         *         Connection flow control window
         */
        public int getRemainingReservation() {
            return remainingReservation;
        }

        /**
         *
         * @return The number of bytes allocated from the Connection flow
         *         control window but not yet written
         */
        public int getUnusedAllocation() {
            return unusedAllocation;
        }

        /**
         * The purpose of this is to avoid the incorrect triggering of a timeout
         * for the following sequence of events:
         * <ol>
         * <li>window update 1</li>
         * <li>allocation 1</li>
         * <li>notify 1</li>
         * <li>window update 2</li>
         * <li>allocation 2</li>
         * <li>act on notify 1 (using allocation 1 and 2)</li>
         * <li>notify 2</li>
         * <li>act on notify 2 (timeout due to no allocation)</li>
         * </ol>
         *
         * @return {@code true} if a notify has been issued but the associated
         *         allocation has not been used, otherwise {@code false}
         */
        public boolean isNotifyInProgress() {
            return notifyInProgress;
        }

        public void useAllocation() {
            unusedAllocation = 0;
            notifyInProgress = false;
        }

        public void startNotify() {
            notifyInProgress = true;
        }

        private int allocate(int allocation) {
            if (remainingReservation >= allocation) {
                remainingReservation -= allocation;
                unusedAllocation += allocation;
                return 0;
            }

            int left = allocation - remainingReservation;
            unusedAllocation += remainingReservation;
            remainingReservation = 0;

            return left;
        }
    }
}
