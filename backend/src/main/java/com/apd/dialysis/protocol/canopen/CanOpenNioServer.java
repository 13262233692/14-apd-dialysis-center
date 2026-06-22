package com.apd.dialysis.protocol.canopen;

import com.apd.dialysis.config.ApdProperties;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Slf4j
@Component
public class CanOpenNioServer {

    private static final long IDLE_CONNECTION_TIMEOUT_MS = 60000L;
    private static final long CONNECTION_WATCHDOG_INTERVAL_MS = 15000L;

    private final ApdProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile Selector selector;
    private volatile ServerSocketChannel serverChannel;
    private volatile ExecutorService acceptorExecutor;
    private volatile ScheduledExecutorService watchdogExecutor;

    private final ConcurrentHashMap<SocketChannel, ChannelContext> channelContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Consumer<CanOpenFrame>, Boolean> frameListeners = new ConcurrentHashMap<>();

    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong totalFramesParsed = new AtomicLong(0);
    private final AtomicLong zombieConnectionsCleaned = new AtomicLong(0);

    public CanOpenNioServer(ApdProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (properties.getHardware().getSimulator().isEnabled()) {
            log.info("Hardware simulator mode enabled, skipping NIO server start");
            return;
        }
        lifecycleLock.lock();
        try {
            if (!running.compareAndSet(false, true)) {
                log.warn("CANopen NIO server already running");
                return;
            }
            doStart();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void doStart() {
        acceptorExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "canopen-nio-server");
            t.setDaemon(true);
            return t;
        });

        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "canopen-conn-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdogExecutor.scheduleAtFixedRate(
                this::cleanupZombieConnections,
                CONNECTION_WATCHDOG_INTERVAL_MS,
                CONNECTION_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        acceptorExecutor.submit(this::runEventLoop);
        log.info("CANopen NIO server starting on port {}", properties.getHardware().getCanopen().getPort());
    }

    @PreDestroy
    public void stop() {
        lifecycleLock.lock();
        try {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            doStop();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void doStop() {
        log.info("CANopen NIO server stopping... totalConnections={}, framesParsed={}, zombiesCleaned={}",
                totalConnections.get(), totalFramesParsed.get(), zombieConnectionsCleaned.get());

        if (selector != null) {
            try {
                selector.wakeup();
            } catch (Exception ignored) {}
        }

        List<SocketChannel> channelsSnapshot = new ArrayList<>(channelContexts.keySet());
        for (SocketChannel ch : channelsSnapshot) {
            closeChannelQuietly(ch);
        }
        channelContexts.clear();

        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {}
            serverChannel = null;
        }
        if (selector != null) {
            try {
                selector.close();
            } catch (IOException ignored) {}
            selector = null;
        }
        if (acceptorExecutor != null) {
            acceptorExecutor.shutdownNow();
            acceptorExecutor = null;
        }
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdownNow();
            watchdogExecutor = null;
        }
        log.info("CANopen NIO server stopped");
    }

    private void runEventLoop() {
        Selector localSelector = null;
        ServerSocketChannel localServer = null;
        try {
            localSelector = Selector.open();
            selector = localSelector;
            localServer = ServerSocketChannel.open();
            serverChannel = localServer;
            localServer.configureBlocking(false);
            localServer.socket().bind(new InetSocketAddress(properties.getHardware().getCanopen().getPort()));
            localServer.register(localSelector, SelectionKey.OP_ACCEPT);

            ByteBuffer readBuffer = ByteBuffer.allocate(properties.getHardware().getCanopen().getBufferSize());

            log.info("CANopen NIO event loop started");

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    int readyCount = localSelector.select(1000);
                    if (readyCount == 0) continue;

                    Iterator<SelectionKey> keyIterator = localSelector.selectedKeys().iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();
                        if (!key.isValid()) continue;

                        try {
                            if (key.isAcceptable()) {
                                handleAccept(key, localSelector);
                            } else if (key.isReadable()) {
                                handleRead(key, readBuffer);
                            }
                        } catch (CancelledKeyException cke) {
                            log.debug("SelectionKey already cancelled");
                            cancelKey(key);
                        } catch (IOException e) {
                            log.warn("NIO key handling error: {}", e.getMessage());
                            cancelKey(key);
                        } catch (Exception e) {
                            log.error("Unexpected NIO error", e);
                            cancelKey(key);
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("CANopen NIO select loop error", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("CANopen NIO event loop fatal error", e);
        } finally {
            log.info("CANopen NIO event loop exiting");
        }
    }

    private void handleAccept(SelectionKey key, Selector sel) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client != null) {
            try {
                client.configureBlocking(false);
                client.socket().setTcpNoDelay(true);
                client.socket().setKeepAlive(true);
                client.socket().setSoTimeout(properties.getHardware().getCanopen().getReadTimeout());

                SelectionKey clientKey = client.register(sel, SelectionKey.OP_READ);
                ChannelContext ctx = new ChannelContext(client);
                clientKey.attach(ctx);
                channelContexts.put(client, ctx);
                totalConnections.incrementAndGet();
                log.info("CANopen device connected: {}, totalChannels={}",
                        client.getRemoteAddress(), channelContexts.size());
            } catch (IOException e) {
                log.error("Error configuring accepted channel", e);
                closeChannelQuietly(client);
            }
        }
    }

    private void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ChannelContext ctx = (ChannelContext) key.attachment();

        buffer.clear();
        int bytesRead;
        try {
            bytesRead = channel.read(buffer);
        } catch (IOException e) {
            if (ctx != null) {
                ctx.markError();
            }
            throw e;
        }

        if (bytesRead == -1) {
            cancelKey(key);
            return;
        }
        if (bytesRead > 0 && ctx != null) {
            ctx.touch();
            buffer.flip();
            parseFramesFromBuffer(buffer);
        }
    }

    private void parseFramesFromBuffer(ByteBuffer buffer) {
        while (buffer.remaining() >= 13) {
            try {
                CanOpenFrame frame = CanOpenFrame.fromByteBuffer(buffer);
                totalFramesParsed.incrementAndGet();
                notifyListeners(frame);
            } catch (Exception e) {
                log.debug("Error parsing CANopen frame", e);
                break;
            }
        }
    }

    private void cancelKey(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        ChannelContext removed = channelContexts.remove(channel);
        try {
            key.cancel();
        } catch (Exception ignored) {}
        closeChannelQuietly(channel);
        log.info("CANopen device disconnected. remainingChannels={}", channelContexts.size());
    }

    private void cleanupZombieConnections() {
        if (!running.get() || channelContexts.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            int cleaned = 0;
            List<SocketChannel> toClose = new ArrayList<>();
            for (ChannelContext ctx : channelContexts.values()) {
                if (ctx.shouldCleanup(now)) {
                    toClose.add(ctx.channel);
                }
            }
            for (SocketChannel ch : toClose) {
                ChannelContext removed = channelContexts.remove(ch);
                if (removed != null) {
                    closeChannelQuietly(ch);
                    cleaned++;
                    zombieConnectionsCleaned.incrementAndGet();
                    log.warn("Cleaned up zombie CANopen connection, idleSince={}ms", now - removed.lastActivityAt);
                }
            }
            if (cleaned > 0) {
                log.info("Zombie connection cleanup finished: removed={}, remaining={}", cleaned, channelContexts.size());
            }
        } catch (Exception e) {
            log.error("Zombie connection cleanup error", e);
        }
    }

    private void closeChannelQuietly(SocketChannel channel) {
        if (channel == null) return;
        try {
            if (channel.isOpen()) {
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    try { key.cancel(); } catch (Exception ignored) {}
                }
                channel.close();
            }
        } catch (IOException ignored) {}
    }

    public void addFrameListener(Consumer<CanOpenFrame> listener) {
        if (listener != null) {
            frameListeners.putIfAbsent(listener, Boolean.TRUE);
        }
    }

    public void removeFrameListener(Consumer<CanOpenFrame> listener) {
        if (listener != null) {
            frameListeners.remove(listener);
        }
    }

    private void notifyListeners(CanOpenFrame frame) {
        for (Consumer<CanOpenFrame> listener : frameListeners.keySet()) {
            try {
                listener.accept(frame);
            } catch (Exception e) {
                log.warn("Frame listener error", e);
            }
        }
    }

    public void sendFrame(CanOpenFrame frame) {
        if (frame == null || channelContexts.isEmpty()) return;
        ByteBuffer buf = frame.toByteBuffer();
        for (ChannelContext ctx : channelContexts.values()) {
            if (!ctx.channel.isConnected() || !ctx.channel.isOpen()) continue;
            try {
                ByteBuffer dup = buf.duplicate();
                while (dup.hasRemaining()) {
                    int written = ctx.channel.write(dup);
                    if (written < 0) break;
                }
                ctx.touch();
            } catch (IOException e) {
                ctx.markError();
                log.debug("Failed to send frame to channel", e);
            }
        }
    }

    public int getActiveChannelCount() {
        return channelContexts.size();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public long getTotalFramesParsed() {
        return totalFramesParsed.get();
    }

    public long getZombieConnectionsCleaned() {
        return zombieConnectionsCleaned.get();
    }

    private static class ChannelContext {
        final SocketChannel channel;
        volatile long lastActivityAt;
        volatile long createdAt;
        volatile boolean hasError;

        ChannelContext(SocketChannel channel) {
            this.channel = channel;
            long now = System.currentTimeMillis();
            this.lastActivityAt = now;
            this.createdAt = now;
            this.hasError = false;
        }

        void touch() {
            lastActivityAt = System.currentTimeMillis();
        }

        void markError() {
            hasError = true;
        }

        boolean shouldCleanup(long now) {
            if (hasError) return true;
            if (!channel.isOpen() || !channel.isConnected()) return true;
            return (now - lastActivityAt) > IDLE_CONNECTION_TIMEOUT_MS;
        }
    }
}
