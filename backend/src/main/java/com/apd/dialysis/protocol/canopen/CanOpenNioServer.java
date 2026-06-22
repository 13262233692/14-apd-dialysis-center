package com.apd.dialysis.protocol.canopen;

import com.apd.dialysis.config.ApdProperties;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Component
public class CanOpenNioServer {

    private final ApdProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private ExecutorService acceptorExecutor;
    private final CopyOnWriteArrayList<Consumer<CanOpenFrame>> frameListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SocketChannel> connectedChannels = new CopyOnWriteArrayList<>();

    public CanOpenNioServer(ApdProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (properties.getHardware().getSimulator().isEnabled()) {
            log.info("Hardware simulator mode enabled, skipping NIO server start");
            return;
        }
        try {
            running.set(true);
            acceptorExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "canopen-nio-server");
                t.setDaemon(true);
                return t;
            });
            acceptorExecutor.submit(this::nioEventLoop);
            log.info("CANopen NIO server starting on port {}", properties.getHardware().getCanopen().getPort());
        } catch (Exception e) {
            log.error("Failed to start CANopen NIO server", e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (selector != null) selector.close();
            if (serverChannel != null) serverChannel.close();
            connectedChannels.forEach(ch -> {
                try { ch.close(); } catch (IOException ignored) {}
            });
            connectedChannels.clear();
        } catch (IOException e) {
            log.error("Error stopping CANopen NIO server", e);
        }
        if (acceptorExecutor != null) {
            acceptorExecutor.shutdownNow();
        }
    }

    private void nioEventLoop() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(properties.getHardware().getCanopen().getPort()));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            ByteBuffer readBuffer = ByteBuffer.allocate(properties.getHardware().getCanopen().getBufferSize());

            while (running.get()) {
                int readyCount = selector.select(1000);
                if (readyCount == 0) continue;

                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key, readBuffer);
                        }
                    } catch (IOException e) {
                        log.warn("NIO key handling error", e);
                        cancelKey(key);
                    }
                }
            }
        } catch (IOException e) {
            log.error("CANopen NIO event loop error", e);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
            connectedChannels.add(client);
            log.info("CANopen device connected: {}", client.getRemoteAddress());
        }
    }

    private void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        buffer.clear();
        int bytesRead = channel.read(buffer);
        if (bytesRead == -1) {
            cancelKey(key);
            return;
        }
        if (bytesRead > 0) {
            buffer.flip();
            parseFramesFromBuffer(buffer);
        }
    }

    private void parseFramesFromBuffer(ByteBuffer buffer) {
        while (buffer.remaining() >= 13) {
            try {
                CanOpenFrame frame = CanOpenFrame.fromByteBuffer(buffer);
                notifyListeners(frame);
            } catch (Exception e) {
                log.debug("Error parsing CANopen frame", e);
                break;
            }
        }
    }

    private void cancelKey(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        connectedChannels.remove(channel);
        key.cancel();
        try {
            channel.close();
        } catch (IOException ignored) {}
        log.info("CANopen device disconnected");
    }

    public void addFrameListener(Consumer<CanOpenFrame> listener) {
        frameListeners.add(listener);
    }

    public void removeFrameListener(Consumer<CanOpenFrame> listener) {
        frameListeners.remove(listener);
    }

    private void notifyListeners(CanOpenFrame frame) {
        for (Consumer<CanOpenFrame> listener : frameListeners) {
            try {
                listener.accept(frame);
            } catch (Exception e) {
                log.warn("Frame listener error", e);
            }
        }
    }

    public void sendFrame(CanOpenFrame frame) {
        ByteBuffer buf = frame.toByteBuffer();
        for (SocketChannel channel : connectedChannels) {
            try {
                if (channel.isConnected()) {
                    channel.write(buf.duplicate());
                }
            } catch (IOException e) {
                log.debug("Failed to send frame to channel", e);
            }
        }
    }
}
