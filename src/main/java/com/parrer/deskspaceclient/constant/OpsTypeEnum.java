package com.parrer.deskspaceclient.constant;

public enum OpsTypeEnum {
    COMMAND(1, "command"), NORMALCOMMAND(2, "normalcommand"),
    DOWNLOAD(3, "download"),
    UNKNOWN(4, "unknown"),
    CONNECT(5, "connect"),
    REGISTRY(6, "registry"),
    HANDSHAKE(7, "handshake"),
    DISCONNECT(8, "disconnect");
    private Integer key;
    private String value;


    OpsTypeEnum(Integer code, String name) {
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
