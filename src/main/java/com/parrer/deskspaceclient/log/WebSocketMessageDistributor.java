package com.parrer.deskspaceclient.log;

import com.parrer.deskspaceclient.constant.OpsTypeEnum;
import com.parrer.deskspaceclient.constant.OsTypeEnum;
import com.parrer.util.JsonUtil;
import com.parrer.util.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.parrer.constant.EnumSupport.esp;

@Component
public class WebSocketMessageDistributor {
    @Autowired
    private LinuxJschService linuxJschService;

    public void distributeMessage(String message) {
        if (StringUtils.isBlank(message)) {
            LogUtil.warn("接受到空消息！");
        }
        LogUtil.info("接受到消息-{}", message);
        WebsocketMessage websocketMessage = JsonUtil.toObject(message, WebsocketMessage.class);
        Integer osType = websocketMessage.getOsType();
        if (OsTypeEnum.UNKNOWN.equals(esp(OsTypeEnum.class).getByKey(osType))
                || OpsTypeEnum.UNKNOWN.equals(esp(OpsTypeEnum.class).getByKey(websocketMessage.getOpsType()))) {
            LogUtil.error("错误消息格式！-{}", message);
        }
        if (OsTypeEnum.LINUX.equals(esp(OsTypeEnum.class).getByKey(osType))) {
            linuxJschService.dealMessage(websocketMessage);
            return;
        }
        LogUtil.info("未支持的消息类型！-{}", message);
    }


}
