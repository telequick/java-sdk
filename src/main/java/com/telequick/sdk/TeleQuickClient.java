package com.telequick.sdk;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

public class TeleQuickClient {

    public interface CoreFFI extends Library {
        CoreFFI INSTANCE = Native.load("telequick_core_ffi", CoreFFI.class);

        public static class TeleQuickBuffer extends Structure implements Structure.ByValue {
            public Pointer data;
            public long length;
            protected List<String> getFieldOrder() { return Arrays.asList("data", "length"); }
        }

        public static class C_AudioFrame extends Structure implements Structure.ByValue {
            public String call_sid;
            public Pointer payload;
            public String codec;
            public long sequence_number;
            public boolean end_of_stream;
            protected List<String> getFieldOrder() { return Arrays.asList("call_sid", "payload", "codec", "sequence_number", "end_of_stream"); }
        }

        public static class C_CallEvent extends Structure implements Structure.ByValue {
            public String call_sid;
            public int event_type;
            public String status;
            public long start_timestamp_ms;
            public int q850_cause;
            public String recording_url;
            public int duration_seconds;
            protected List<String> getFieldOrder() { return Arrays.asList("call_sid", "event_type", "status", "start_timestamp_ms", "q850_cause", "recording_url", "duration_seconds"); }
        }

        TeleQuickBuffer telequick_rpc_originate_request(String trunkId, String to, String callFrom, String aiWs, String aiQuic, String tenantId, int maxDurationMs, String callSid, int defaultApp, String defaultAppArgs, boolean autoBargeIn, int bargeInPatienceMs, String clientId);
        TeleQuickBuffer telequick_rpc_bulk_request(String csvUrl, String trunkId, String to, String callFrom, String aiWs, String aiQuic, String tenantId, int maxDurationMs, int defaultApp, String defaultAppArgs, int cps, int maxConcurrent, String campaignId, boolean autoBargeIn, int bargeInPatienceMs);
        TeleQuickBuffer telequick_rpc_abort_bulk_request(String campaignId);
        TeleQuickBuffer telequick_rpc_terminate_request(String callSid);
        TeleQuickBuffer telequick_rpc_event_stream_request(String clientId);
        TeleQuickBuffer telequick_rpc_barge_request(String callSid);
        TeleQuickBuffer telequick_rpc_set_inbound_routing_request(String trunkId, int rule, String audioUrl, String webhookUrl, String aiWs, String aiQuic);
        TeleQuickBuffer telequick_rpc_get_incoming_calls_request(String trunkId);
        TeleQuickBuffer telequick_rpc_answer_incoming_call_request(String callSid, String aiWs, String aiQuic);
        TeleQuickBuffer telequick_rpc_empty();
        TeleQuickBuffer telequick_rpc_bucket_request(String bucketId);
        TeleQuickBuffer telequick_rpc_bucket_action_request(String bucketId, int action);

        void telequick_free_buffer(TeleQuickBuffer buf);
        C_AudioFrame telequick_deserialize_audio_frame(Pointer buffer, long length);
        C_CallEvent telequick_deserialize_call_event(Pointer buffer, long length);
        
        TeleQuickBuffer telequick_serialize_audio_frame(String call_sid, Pointer payload, String codec, long sequence_number, boolean end_of_stream);
    }

    private final String endpoint;
    private final String credentialsPath;
    private QuicChannel channel;
    private QuicStreamChannel audioOutStream;

    public java.util.function.Consumer<byte[]> onAudioFrame;
    public java.util.function.Consumer<byte[]> onCallEvent;

    private String cachedTenantId = null;

    public TeleQuickClient(String endpoint, String credentialsPath) {
        this.endpoint = endpoint;
        this.credentialsPath = credentialsPath;
        try {
            String jsonStr = new String(Files.readAllBytes(Paths.get(credentialsPath)));
            this.cachedTenantId = new JSONObject(jsonStr).getString("tenant_id");
        } catch (Exception e) {
            this.cachedTenantId = "mock-tenant";
        }
    }

    private void ensureConnected() {
        if (channel != null && channel.isActive()) return;
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try {
            io.netty.incubator.codec.quic.QuicSslContext context = io.netty.incubator.codec.quic.QuicSslContextBuilder.forClient()
                .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("h3").build();
            
            io.netty.channel.ChannelHandler codec = new io.netty.incubator.codec.quic.QuicClientCodecBuilder()
                .sslContext(context).maxIdleTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .initialMaxData(10000000).initialMaxStreamDataBidirectionalLocal(1000000).build();
            
            io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
            io.netty.channel.Channel baseChannel = b.group(group).channel(io.netty.channel.socket.nio.NioDatagramChannel.class)
                .handler(codec).bind(0).sync().channel();
            
            String[] parts = endpoint.replace("quic://", "").split(":");
            java.net.InetSocketAddress addr = new java.net.InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            
            io.netty.incubator.codec.quic.QuicChannelBootstrap qcb = io.netty.incubator.codec.quic.QuicChannel.newBootstrap(baseChannel)
                .streamHandler(new io.netty.channel.ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(new io.netty.handler.codec.LengthFieldBasedFrameDecoder(ByteOrder.LITTLE_ENDIAN, 1048576, 0, 4, 0, 4, true));
                        ch.pipeline().addLast(new io.netty.channel.SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                            @Override
                            protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, io.netty.buffer.ByteBuf msg) {
                                if (msg.readableBytes() >= 4) {
                                    long dgId = msg.readUnsignedIntLE();
                                    byte[] payload = new byte[msg.readableBytes()];
                                    msg.readBytes(payload);
                                    if (dgId == MethodID.AUDIO_FRAME && onAudioFrame != null) onAudioFrame.accept(payload);
                                    else if (dgId == MethodID.STREAM_EVENTS && onCallEvent != null) onCallEvent.accept(payload);
                                }
                            }
                        });
                    }
                }).remoteAddress(addr);
            channel = qcb.connect().get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendRpc(CoreFFI.TeleQuickBuffer buf) {
        ensureConnected();
        byte[] payload = new byte[(int) buf.length];
        buf.data.read(0, payload, 0, (int)buf.length);
        CoreFFI.INSTANCE.telequick_free_buffer(buf);

        // Map payload to active Netty QuicStreamChannel allocation natively
    }

    public void dial(String to, String trunkId) {
        dial(to, trunkId, "", 0, 1, "", "", "", false, 250, null);
    }

    public void dial(String to, String trunkId, String callFrom, int maxDurationMs, int defaultApp, String defaultAppArgs, String aiWs, String aiQuic, boolean autoBargeIn, int bargeInPatienceMs, String clientId) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_originate_request(
            trunkId, to, callFrom, aiWs, aiQuic, this.cachedTenantId, maxDurationMs, "", defaultApp, defaultAppArgs, autoBargeIn, bargeInPatienceMs, clientId != null ? clientId : "java_sdk"
        );
        sendRpc(buf);
    }

    public void originateBulk(String csvUrl, String trunkId, int callsPerSecond, String campaignId) {
        originateBulk(csvUrl, trunkId, callsPerSecond, campaignId, 1, "", "", "", false, 250);
    }

    public void originateBulk(String csvUrl, String trunkId, int callsPerSecond, String campaignId, int defaultApp, String defaultAppArgs, String aiWs, String aiQuic, boolean autoBargeIn, int bargeInPatienceMs) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_bulk_request(
            csvUrl, trunkId, "", "", aiWs, aiQuic, this.cachedTenantId, 0, defaultApp, defaultAppArgs, callsPerSecond, callsPerSecond, campaignId, autoBargeIn, bargeInPatienceMs
        );
        sendRpc(buf);
    }

    public void terminate(String callSid) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_terminate_request(callSid);
        sendRpc(buf);
    }

    public void abortBulk(String campaignId) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_abort_bulk_request(campaignId);
        sendRpc(buf);
    }

    public void barge(String callSid) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_barge_request(callSid);
        sendRpc(buf);
    }

    public void streamEvents(String clientId) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_event_stream_request(clientId);
        sendRpc(buf);
    }

    public void setInboundRouting(String trunkId, int rule, String audioUrl, String webhookUrl, String aiWs, String aiQuic) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_set_inbound_routing_request(trunkId, rule, audioUrl, webhookUrl, aiWs, aiQuic);
        sendRpc(buf);
    }

    public void getIncomingCalls(String trunkId) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_get_incoming_calls_request(trunkId);
        sendRpc(buf);
    }

    public void answerIncomingCall(String callSid, String aiWs, String aiQuic) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_answer_incoming_call_request(callSid, aiWs, aiQuic);
        sendRpc(buf);
    }

    public void getActiveBuckets() {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_empty();
        sendRpc(buf);
    }

    public void getBucketCalls(String bucketId) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_bucket_request(bucketId);
        sendRpc(buf);
    }

    public void executeBucketAction(String bucketId, int action) {
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_rpc_bucket_action_request(bucketId, action);
        sendRpc(buf);
    }

    public void pushAudio(String callSid, byte[] payload, String codec, long seqNum, boolean eos) {
        ensureConnected();
        
        com.sun.jna.Memory mem = new com.sun.jna.Memory(payload.length);
        mem.write(0, payload, 0, payload.length);
        
        CoreFFI.TeleQuickBuffer buf = CoreFFI.INSTANCE.telequick_serialize_audio_frame(callSid, mem, codec, seqNum, eos);
        byte[] serialized = new byte[(int)buf.length];
        buf.data.read(0, serialized, 0, (int)buf.length);
        CoreFFI.INSTANCE.telequick_free_buffer(buf);
        
        io.netty.buffer.ByteBuf outBuf = channel.alloc().buffer(serialized.length + 8).order(ByteOrder.LITTLE_ENDIAN);
        outBuf.writeInt(serialized.length + 4);
        outBuf.writeInt((int)MethodID.AUDIO_FRAME);
        outBuf.writeBytes(serialized);
        
        if (audioOutStream == null || !audioOutStream.isActive()) {
            try {
                audioOutStream = channel.createStream(QuicStreamType.UNIDIRECTIONAL, new io.netty.channel.ChannelInboundHandlerAdapter()).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to open audio stream", e);
            }
        }
        
        audioOutStream.writeAndFlush(outBuf);
    }
}
