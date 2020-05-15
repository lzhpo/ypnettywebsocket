package com.lzhpo.ypnettywebsocket.service.socket;

import com.lzhpo.ypnettywebsocket.constant.MyConstant;
import com.lzhpo.ypnettywebsocket.entity.FailClientMessage;
import com.lzhpo.ypnettywebsocket.service.socket.impl.NoticeConsumerCenterImpl;
import com.lzhpo.ypnettywebsocket.util.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Date;

/**
 * Netty通知事件
 *
 * @author lzhpo
 */
@Slf4j
public class NoticeChannelHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static MyRedisTemplateUtil myRedisTemplateUtil;
    static {
        myRedisTemplateUtil = SpringUtil.getBean(MyRedisTemplateUtil.class);
    }

    /** 心跳丢失计数器 */
    private int counter;

    /**
     * 【收到客户端的消息的时候调用此事件】
     *
     * <p>第一次发消息和后端鉴权数据格式：需要加入的管道名称@#@clientId
     * 第一次连接需要传入客户端的clientId，客户端自定义的。比如：MAC地址、IP....一定要是唯一的！
     *
     * String[] contexts = msg.text().split("@#@");
     * contexts[0]：管道名称
     * contexts[1]：客户端传来的clientId
     *
     * Eg：channel_global@#@1   这个channel_global表示全局管道，1就表示用户ID，也就是接收者。
     *
     * <p>客户端发心跳检测数据格式：所在的管道名称-heart_beat
     * contexts[1]-heart_beat {@link MyConstant#HEART_BEAT}
     *
     * <p>改进：由于MAC地址带有-
     * 所以分隔符改为@#@
     *
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        synchronized (ctx.channel().id().toString()) {
            Channel channel = ctx.channel();
            String[] contexts = msg.text().split(MyConstant.SPLIT_CLIENT_AND_HEART_BEAT);
            System.out.println("分割内容：" +Arrays.toString(contexts));
            int splitTwo = 2;
            // 如果不是分割的两个字符串，直接在前端回显
            if (contexts.length != splitTwo) {
                // 客户端发送的内容
                String content = org.apache.commons.lang.StringUtils.join(contexts, "[]");
                TextWebSocketFrame frame = new TextWebSocketFrame(content);
                // 推送到前端显示
                channel.writeAndFlush(frame);
                log.info("收到客户端  [{}]  的消息 [{}] ，已向客户端回显！", channel.id(), content);
            } else {
                if (contexts[1].equals(MyConstant.HEART_BEAT)) {
                    log.info("收到客户端channelId为 [{}] 的心跳包!", channel.id());
                } else if (!StringUtils.isEmpty(contexts[1])){
                    channel.writeAndFlush(new TextWebSocketFrame("客户端ID：" + channel.id()));
                    NoticeConsumerCenterImpl.addReceiver(contexts[1]);
                    ChannelIdPool.add(contexts[1], channel.id());
                    addToChannelGroup(contexts[0], channel);
                    // 将此客户端的clientId和channelId存入Redis，断开连接的时候就删除！
                    myRedisTemplateUtil.set(contexts[1], channel.id().toString());
                }
            }
            counter = 0;
        }
    }

    /**
     * 心跳监测
     *
     * @param ctx
     * @param evt
     * @throws Exception
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        synchronized (ctx.channel().id().toString()) {
            // ChannelHandlerContext要是心跳机制事件类型IdleStateEvent
            LocalDateTime localDateTime = LocalDateTime.now();
            log.info("当前轮询时间 [{}]", DateUtil.localDateToString(localDateTime));
            Channel channel = ctx.channel();
            if (evt instanceof IdleStateEvent) {
                if (counter >= MyConstant.HEART_BEAT_DISCONNECT_POLL_NUM) {
                    log.error("已经轮询 [{}] 次没收到客户端channelId为 [{}] 的心跳了！已将其断开连接并删除！",
                            MyConstant.HEART_BEAT_DISCONNECT_POLL_NUM, channel.id());
                    String clientId = ChannelIdPool.get(channel.id());
                    // 记录错误信息，只记录ChannelIdPool中与业务关联的[已鉴权的]
                    if (!StringUtils.isEmpty(clientId)) {
                        String channelIdFromRedis = myRedisTemplateUtil.get(clientId);
                        removeToChannelGroupByChannel(channelIdFromRedis, channel);
                        // 删除Redis中的数据与此关联的数据
                        myRedisTemplateUtil.delete(channelIdFromRedis);
                        // 记录错误信息
                        FailClientMessage failClientMessage = new FailClientMessage();
                        System.out.println("clientId：" +clientId);
                        failClientMessage.setClientId(clientId);
                        failClientMessage.setChannelId(channel.id().toString());
                        failClientMessage.setDisConnectTime(new Date());
                        myRedisTemplateUtil.set(MyConstant.FAIL_CLIENT +clientId, JsonUtils.toJson(failClientMessage));
                    }
                    // 移除与此关联的ChannelIdPool
                    ChannelIdPool.remove(channel.id());
                    // 关闭此TCP连接
                    ctx.channel().close().sync();
                    counter = 0;
                } else {
                    counter++;
                    log.error("客户端channelId为 [{}] 开始已经丢失 [{}] 次心跳包！", ctx.channel().id(), counter);
                }
            }
        }
    }

    /**
     * 关闭tcp连接
     * <p>
     * 当一个TCP连接关闭后，对应的Channel会自动从ChannelGroup移除，所以不需要手动去移除关闭的Channel。
     *
     * 如果非要移除的话：ctx.channel().close().sync();
     * 就可以将此客户端的ChannelHandlerContext移除！
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        log.info("handlerRemoved...");
        super.handlerRemoved(ctx);
    }

    /**
     * 接入了新连接
     * <p>
     * 加入管道的逻辑放在客户端连接之后，发送指定格式的消息再加。
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        log.info("新接入channelId为 [{}] 的客户端", channelId);
    }

    /**
     * 客户端断开连接
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        synchronized (ctx.channel().id().toString()) {
            ChannelId channelId = ctx.channel().id();
            // 删除Redis中的channelId
            myRedisTemplateUtil.delete(channelId.toString());
            log.info("客户端channelId为 [{}] 已断开链接", channelId);
            // 删除ChannelIdPool中的channelId
            ChannelIdPool.remove(channelId);
            ctx.channel().close().sync();
            super.channelInactive(ctx);
        }
    }

    /**
     * 客户端报错
     *
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("客户端channelId为 [{}] 报错：[{}]", ctx.channel().id(), cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 加入管道
     *
     * @param type
     * @param channel
     */
    private void addToChannelGroup(String type, Channel channel) {
        if (ChannelGroupTypeEnum.CHANNEL_GROUP_SMS.getType().equals(type)) {
            MyConstant.SMS_CHANNELS.add(channel);
        } else if (ChannelGroupTypeEnum.CHANNEL_GROUP_AI_TRASH_SAN.getType().equals(type)) {
            MyConstant.AI_CHANNELS.add(channel);
        } else if (ChannelGroupTypeEnum.CHANNEL_GROUP_GLOBAL.getType().equals(type)) {
            MyConstant.GLOBAL_CHANNELS.add(channel);
        }
    }

    /**
     * 从管道移除
     *
     * @param type
     * @param channel
     */
    private void removeToChannelGroupByChannel(String type, Channel channel) {
        if (ChannelGroupTypeEnum.CHANNEL_GROUP_SMS.getType().equals(type)) {
            MyConstant.SMS_CHANNELS.remove(channel);
        } else if (ChannelGroupTypeEnum.CHANNEL_GROUP_AI_TRASH_SAN.getType().equals(type)) {
            MyConstant.AI_CHANNELS.remove(channel);
        } else if (ChannelGroupTypeEnum.CHANNEL_GROUP_GLOBAL.getType().equals(type)) {
            MyConstant.GLOBAL_CHANNELS.remove(channel);
        }
    }

}