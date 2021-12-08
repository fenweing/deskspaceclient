package com.parrer.deskspaceclient.log;

import lombok.Data;

import java.util.List;

@Data
public class WebsocketResponse {
    private Integer osType;
    private Integer opsType;
    private String connectionUuid;
    private String data;
    private String requestNum;
    private String host;
    private List<ConnectInfo> connectInfoList;
    private ConnectInfo connectInfo;
    public static WebsocketResponse ofData(String data) {
        WebsocketResponse response = new WebsocketResponse();
        response.setData(data);
        return response;
    }
}
