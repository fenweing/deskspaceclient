package com.parrer.deskspaceclient.log;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(exclude = {"requestNum"})
public class ConnectInfo {
    private String user;
    private String host;
    private Integer remotePort;
    private String password;
    private String requestNum;
}
