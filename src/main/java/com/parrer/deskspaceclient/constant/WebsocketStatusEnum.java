package com.parrer.deskspaceclient.constant;

public enum WebsocketStatusEnum {
    CONNECTED(0, "连接成功"), DISCONNECTED(1, "连接断开");
    private Integer key;
    private String value;


    WebsocketStatusEnum(Integer code, String name) {
        this.key = code;
        this.value = name;
    }

    public Integer getKey() {
        return key;
    }
    public String getValue() {
        return value;
    }

}
