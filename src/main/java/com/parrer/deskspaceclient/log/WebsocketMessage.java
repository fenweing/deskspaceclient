package com.parrer.deskspaceclient.log;

import lombok.Data;

@Data
public class WebsocketMessage {
    private Integer osType;
    private Integer opsType;
    private String command;
    private String filePath;
    private ConnectInfo connectInfo;
    private String connectionUuid;
    private String requestNum;
}
