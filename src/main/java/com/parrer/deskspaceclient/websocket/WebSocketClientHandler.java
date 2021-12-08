package com.parrer.deskspaceclient.websocket;

import com.parrer.deskspaceclient.constant.WebsocketStatusEnum;
import com.parrer.deskspaceclient.log.WebSocketMessageDistributor;
import com.parrer.util.LogUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Base64Utils;

import java.nio.charset.StandardCharsets;

@Component
public class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
    @Autowired
    private ClientByNetty clientByNetty;
    @Autowired
    private WebSocketMessageDistributor messageDistributor;
    //握手的状态信息
    WebSocketClientHandshaker handshaker;
    //netty自带的异步处理
    ChannelPromise handshakeFuture;
    private String rn = "\r\n";
    private final static String ENCRYPT_PREFIX = "Pp/eEiT[";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            Channel ch = ctx.channel();
            //进行握手操作0
            if (!this.handshaker.isHandshakeComplete()) {
                configFinishHandshake((FullHttpResponse) msg, ch);
            } else if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                LogUtil.warn("Unexpected FullHttpResponse (getStatus=" + response.status() + ", content=" + response.content().toString(StandardCharsets.UTF_8) + ")");
            } else {
                dealRecivedMessage((WebSocketFrame) msg, ch);
            }
        } catch (Exception e) {
            LogUtil.error(e, "error occurred when handle channel read info.");
        }
    }

    private void configFinishHandshake(FullHttpResponse msg, Channel ch) {
        FullHttpResponse response;
        try {
            response = msg;
            //握手协议返回，设置结束握手
            this.handshaker.finishHandshake(ch, response);
            //设置成功
            this.handshakeFuture.setSuccess();
            LogUtil.info("握手结束，服务端的消息头-{}", response.headers());
        } catch (WebSocketHandshakeException var7) {
            FullHttpResponse res = msg;
            String errorMsg = String.format("握手失败,status:%s,reason:%s", res.status(), res.content().toString(StandardCharsets.UTF_8));
            this.handshakeFuture.setFailure(new Exception(errorMsg));
        }
    }

    private void dealRecivedMessage(WebSocketFrame msg, Channel ch) {
        //接收服务端的消息
        WebSocketFrame frame = msg;
        //文本信息
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
//            String decryptedMsg = checkAndDecryptText(textFrame.text());
//            dealMessage(decryptedMsg);
            dealMessage(textFrame.text());
        }
        //二进制信息
        if (frame instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binFrame = (BinaryWebSocketFrame) frame;
            LogUtil.info("BinaryWebSocketFrame");
        }
        //ping信息
        if (frame instanceof PongWebSocketFrame) {
            LogUtil.info("WebSocket Client received pong");
        }
        //关闭消息
        if (frame instanceof CloseWebSocketFrame) {
            LogUtil.info("receive close frame");
            ch.close();
        }
    }

    private String checkAndDecryptText(String text) {
        if (StringUtils.startsWith(text, ENCRYPT_PREFIX)) {
            LogUtil.info("客户端接收到加密消息。");
            String encrypted = text.substring(ENCRYPT_PREFIX.length());
            String msg = new String(Base64Utils.decode(encrypted.getBytes()));
            return msg;
        } else {
            return text;
        }
    }

    private void dealMessage(String text) {
        if (StringUtils.isBlank(text)) {
            LogUtil.warn("接受到空消息！");
            return;
        }
        messageDistributor.distributeMessage(text);
    }

    /**
     * Handler活跃状态，表示连接成功
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientByNetty.setStatus(WebsocketStatusEnum.CONNECTED.getKey());
        LogUtil.info("与服务端连接成功");
    }

    /**
     * 非活跃状态，没有连接远程主机的时候。
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        clientByNetty.setStatus(WebsocketStatusEnum.DISCONNECTED.getKey());
        LogUtil.warn("主机关闭");
    }

    /**
     * 异常处理
     *
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        clientByNetty.setStatus(WebsocketStatusEnum.DISCONNECTED.getKey());
        LogUtil.error(cause, "连接异常!");
        ctx.close();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.handshakeFuture = ctx.newPromise();
    }

    public WebSocketClientHandshaker getHandshaker() {
        return handshaker;
    }

    public void setHandshaker(WebSocketClientHandshaker handshaker) {
        this.handshaker = handshaker;
    }

    public ChannelPromise getHandshakeFuture() {
        return handshakeFuture;
    }

    public void setHandshakeFuture(ChannelPromise handshakeFuture) {
        this.handshakeFuture = handshakeFuture;
    }

    public ChannelFuture handshakeFuture() {
        return this.handshakeFuture;
    }
}
