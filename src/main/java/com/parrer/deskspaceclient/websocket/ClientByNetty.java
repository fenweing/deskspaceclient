package com.parrer.deskspaceclient.websocket;

import com.parrer.deskspaceclient.constant.OpsTypeEnum;
import com.parrer.deskspaceclient.constant.WebsocketStatusEnum;
import com.parrer.deskspaceclient.log.ConnectInfo;
import com.parrer.deskspaceclient.log.WebsocketResponse;
import com.parrer.thread.ScheduledExecutor;
import com.parrer.util.CollcUtil;
import com.parrer.util.JsonUtil;
import com.parrer.util.LogUtil;
import com.sun.javafx.binding.StringFormatter;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * 基于websocket的netty客户端
 */
@Component
public class ClientByNetty {
    @Value("${deskspaceServer.host}")
    private String serverHost;
    @Value("${deskspaceServer.port}")
    private Integer serverPort;
    @Value("${deskspaceServer.appName}")
    private String serverAppName;
    @Autowired
    private ScheduledExecutor scheduledExecutor;
    @Autowired
    private WebSocketClientHandler clientHandler;
    private final static String SERVER_URL_PATTERN = "ws://%s:%s/%s/ws";
    private Channel channel;
    private Integer status = WebsocketStatusEnum.DISCONNECTED.getKey();

    public void init() throws Exception {
        LogUtil.info("初始化netty-websocket>>。。。");
        //netty基本操作，线程组
        EventLoopGroup group = new NioEventLoopGroup();
        //netty基本操作，启动类
        Bootstrap boot = new Bootstrap();
        boot.option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .group(group)
                .handler(new LoggingHandler(LogLevel.INFO))
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("http-codec", new HttpClientCodec());
                        pipeline.addLast("aggregator", new HttpObjectAggregator(1024 * 1024 * 10));
                        pipeline.addLast("hookedHandler", clientHandler);
                    }
                });
        URI websocketURI = new URI(getServerUrl(serverHost, serverPort, serverAppName));
        HttpHeaders httpHeaders = new DefaultHttpHeaders();
        //进行握手
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(websocketURI, WebSocketVersion.V13, (String) null, true, httpHeaders);
        //客户端与服务端连接的通道，final修饰表示只会有一个
        channel = boot.connect(websocketURI.getHost(), websocketURI.getPort()).sync().channel();
        WebSocketClientHandler handler = (WebSocketClientHandler) channel.pipeline().get("hookedHandler");
        handler.setHandshaker(handshaker);
        handshaker.handshake(channel);
        //阻塞等待是否握手成功
        handler.handshakeFuture().sync();
        LogUtil.info("握手成功");
        //给服务端发送的内容，如果客户端与服务端连接成功后，可以多次掉用这个方法发送消息
        sendMessage("客户端连接成功。");
        sendConnectionRegistryInfo();
        sendPong();
        LogUtil.info("初始化netty-websocket结束<<");
    }

    private void sendConnectionRegistryInfo() {
        List<ConnectInfo> connectInfoList = getConnectionRegistryInfo();
        WebsocketResponse response = new WebsocketResponse();
        response.setOpsType(OpsTypeEnum.REGISTRY.getKey());
        response.setConnectInfoList(connectInfoList);
        sendMessage(JsonUtil.toString(response));
        LogUtil.info("发送连接注册信息成功！-{}", response);

    }

    private List<ConnectInfo> getConnectionRegistryInfo() {
        return CollcUtil.ofList();
    }

    private String getServerUrl(String host, Integer port, String serverAppName) {
        return StringFormatter.format(SERVER_URL_PATTERN, host, port, serverAppName).getValue();
    }

    public void sendMessage(WebsocketResponse response) {
        if (response == null) {
            LogUtil.error("空消息！");
            return;
        }
        sendMessage(JsonUtil.toString(response));
    }

    public void sendMessage(String msg) {
        if (StringUtils.isBlank(msg)) {
            LogUtil.error("空消息！");
            return;
        }
        TextWebSocketFrame frame = new TextWebSocketFrame(msg);
        channel.writeAndFlush(frame).addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                LogUtil.info("消息发送成功-{}", msg);
            } else {
                LogUtil.error(channelFuture.cause(), "消息发送失败");
            }
        });
    }

    private void sendPong() {
        scheduledExecutor.scheduleAtFixedRate(20L, 600L, TimeUnit.SECONDS,
                () -> {
                    if (WebsocketStatusEnum.CONNECTED.getKey().equals(status)) {
                        WebsocketResponse response = new WebsocketResponse();
                        response.setOpsType(OpsTypeEnum.HANDSHAKE.getKey());
                        sendMessage(response);
                    }
                });
    }


    public void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.write(new CloseWebSocketFrame());
            channel.close();
        } catch (Exception e) {
            LogUtil.error(e, "关闭websocket连接失败！");
        }
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

}
