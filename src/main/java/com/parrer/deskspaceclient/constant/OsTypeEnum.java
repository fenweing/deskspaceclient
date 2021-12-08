package com.parrer.deskspaceclient.constant;

public enum OsTypeEnum {
    LINUX(1, "linux"), WINDOWS(2, "windows"),
    UNKNOWN(3, "unknown");
    private Integer key;
    private String value;


    OsTypeEnum(Integer code, String name) {
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
