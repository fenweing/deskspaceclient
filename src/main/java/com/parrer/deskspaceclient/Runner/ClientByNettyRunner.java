package com.parrer.deskspaceclient.Runner;

import com.parrer.deskspaceclient.websocket.ClientByNetty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ClientByNettyRunner implements ApplicationRunner {
    @Autowired
    private ClientByNetty clientByNetty;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        clientByNetty.init();
    }
}
