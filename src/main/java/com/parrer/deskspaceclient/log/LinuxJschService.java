package com.parrer.deskspaceclient.log;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.parrer.component.BaseImpl;
import com.parrer.deskspaceclient.constant.OpsTypeEnum;
import com.parrer.deskspaceclient.constant.OsTypeEnum;
import com.parrer.deskspaceclient.websocket.ClientByNetty;
import com.parrer.exception.ServiceException;
import com.parrer.thread.ThreadExecutor;
import com.parrer.util.CollcUtil;
import com.parrer.util.JsonUtil;
import com.parrer.util.LogUtil;
import com.parrer.util.StringUtil;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static com.parrer.constant.EnumSupport.esp;

@Component
public class LinuxJschService extends BaseImpl implements InitializingBean {
    @Autowired
    private ClientByNetty clientByNetty;
    @Autowired
    private ThreadExecutor threadExecutor;
    private Integer remotePort = 22;
    private Map<ConnectInfo, Session> sessionMap = new HashMap<>();
    private Map<Session, HashSet<Channel>> sessionChannelMap = new HashMap<Session, HashSet<Channel>>();
    private Map<Session, HashSet<String>> sessionRequestNumMap = new HashMap<>();
    private Map<String, ConnectInfo> connectionUuidMap = new HashMap<>();
    private Map<String, ResultOpsThread> requestResultOpsThreadMap = new HashMap<>();
    private Map<String, CommandOpsWrapper> requestCommandOpsWrapperMap = new HashMap<>();
    private Map<String, String> requestNumConnectUuidMap = new HashMap<>();

    public class ResultOpsThread implements Callable {
        @Setter
        private String requestNum;

        @Setter
        private BufferedReader inputBufferedReader;
        private boolean exit = false;

        public ResultOpsThread(String requestNum, BufferedReader inputBufferedReader) {
            this.requestNum = requestNum;
            this.inputBufferedReader = inputBufferedReader;
        }

        public void setExit() {
            exit = true;
        }

        @Override
        public Object call() throws Exception {
            String line;
            StringBuilder result = new StringBuilder();
            System.out.println("+++++in res thread++++++");
            while (true && !exit) {
                System.out.println("+++++in res loop++++++");
                try {
                    if (((line = inputBufferedReader.readLine()) != null)) {
                        System.out.println("++++++++++" + line);
                        send(OpsTypeEnum.COMMAND.getKey(), requestNum, line);
                    }
                } catch (Exception e) {
                    LogUtil.error(e, "处理结果线程出错！");
                    Thread.sleep(2000L);
                }
//                result.append(line).append("\r\n");
            }
            close();
            System.out.println("++++++result thread");
            return StringUtils.EMPTY;
        }

        public void close() {
            try {
                inputBufferedReader.close();
            } catch (IOException e) {
                LogUtil.error(e, "关闭结果读取流出错！");
            }
        }
    }

    public static class CommandOpsWrapper {
        @Setter
        private String requestNum;
        @Setter
        private PrintStream commandPrintStream;
        @Setter
        private ResultOpsThread resultOpsThread;

        public CommandOpsWrapper(String requestNum, PrintStream commandPrintStream) {
            this.requestNum = requestNum;
            this.commandPrintStream = commandPrintStream;
        }

        public void operate(String command) {
            if (StringUtils.isBlank(command)) {
                return;
            }
            if ("exit".equals(command)) {
                resultOpsThread.setExit();
                commandPrintStream.println("ls");
                commandPrintStream.println(command);
                close();
                return;
            }
            commandPrintStream.println(command);
        }

        public void close() {
            commandPrintStream.close();
        }

    }

    @Override
    public void afterPropertiesSet() throws Exception {
        addStrategyGroupNx(String.valueOf(OsTypeEnum.LINUX.getKey()))
                .addStrategy(OpsTypeEnum.COMMAND.getKey(), (Consumer<WebsocketMessage>) message -> dealNormalCommand(message))
                .addStrategy(OpsTypeEnum.UNKNOWN.getKey(), (Consumer<WebsocketMessage>) message -> LogUtil.warn("不支持的操作类型-{}", message))
                .addStrategy(OpsTypeEnum.CONNECT.getKey(), (Consumer<WebsocketMessage>) message -> linuxConnect(message))
                .addStrategy(OpsTypeEnum.DISCONNECT.getKey(), (Consumer<WebsocketMessage>) message -> disconnetSession(message));
    }

    public void disconnetSession(WebsocketMessage websocketMessage) {
        LogUtil.info("关闭session>>。。。");
        String connectionUuid = websocketMessage.getConnectionUuid();
        ConnectInfo connectInfo = connectionUuidMap.get(connectionUuid);
        Session session = sessionMap.get(connectInfo);
        if (session == null) {
            return;
        }
        HashSet<String> requestNumSet = sessionRequestNumMap.get(session);
        if (CollectionUtils.isEmpty(requestNumSet)) {
            return;
        }
        for (String requestNum : requestNumSet) {
            try {
                CommandOpsWrapper commandOpsWrapper = requestCommandOpsWrapperMap.get(requestNum);
                if (commandOpsWrapper == null) {
                    continue;
                }
                commandOpsWrapper.operate("exit");
                requestCommandOpsWrapperMap.remove(requestNum);
                requestResultOpsThreadMap.remove(requestNum);
            } catch (Exception e) {
                LogUtil.error(e, "关闭session出错！-{}", connectInfo);
            }
        }
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            LogUtil.error(e, "关闭session中关闭channel中sleep出错！");
        }
        HashSet<Channel> channels = sessionChannelMap.get(session);
        if (CollectionUtils.isNotEmpty(channels)) {
            for (Channel channel : channels) {
                try {
                    channel.disconnect();
                } catch (Exception e) {
                    LogUtil.error(e, "关闭session中关闭channel出错！");
                }
            }
        }
        sessionChannelMap.remove(session);
        session.disconnect();
        connectionUuidMap.remove(connectionUuid);
        sessionMap.remove(connectInfo);
        LogUtil.info("关闭session结束<<");
    }

    public Session createSession(ConnectInfo connectInfo) {
        Session existsSession = sessionMap.get(connectInfo);
        if (existsSession != null) {
            return existsSession;
        }
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(connectInfo.getUser(), connectInfo.getHost(), connectInfo.getRemotePort());
            session.setPassword(connectInfo.getPassword());
            Properties prop = new Properties();
            prop.put("StrictHostKeyChecking", "no");
            session.setConfig(prop);
            session.setPort(remotePort);
            session.connect();
            sessionMap.put(connectInfo, session);
            return session;
        } catch (JSchException e) {
            throw new ServiceException(e, "创建jsch session失败！-{}", connectInfo);
        }
    }

    public void send(Integer opsType, String requestNum, String message) {
        if (StringUtils.isBlank(requestNum) || StringUtils.isBlank(message)
                || opsType == null) {
            LogUtil.error("参数错误！");
            return;
        }
        WebsocketResponse response = WebsocketResponse.ofData(message);
        response.setRequestNum(requestNum);
        response.setOpsType(opsType);
        response.setConnectionUuid(requestNumConnectUuidMap.get(requestNum));
        clientByNetty.sendMessage(response);
    }

    public void send(String requestNum, WebsocketResponse response) {
        if (StringUtils.isBlank(requestNum) || null == response) {
            return;
        }
        response.setRequestNum(requestNum);
        clientByNetty.sendMessage(response);
    }

    public void sendError(WebsocketMessage message, String errorMessage) {
        if (null == message || StringUtils.isBlank(errorMessage)) {
            return;
        }
        WebsocketResponse response = WebsocketResponse.ofData(errorMessage + "【" + JsonUtil.toString(message) + "】");
        response.setRequestNum(message.getRequestNum());
        clientByNetty.sendMessage(response);
    }

    public void dealMessage(WebsocketMessage message) {
        if (message == null) {
            return;
        }
        String requestNum = message.getRequestNum();
        if (StringUtils.isBlank(requestNum)) {
            sendError(message, "参数错误！");
            return;
        }
        Integer osType = message.getOsType();
        Integer opsType = message.getOpsType();
        if (osType == null || opsType == null) {
            sendError(message, "参数错误！");
            return;
        }
        Integer osTypeKey = ((OsTypeEnum) esp(OsTypeEnum.class).getByKey(osType)).getKey();
        Integer opsTypeKey = ((OpsTypeEnum) esp(OpsTypeEnum.class).getByKey(opsType)).getKey();
        Object linuxOps = getStrategyGroup(String.valueOf(osTypeKey)).getStrategy(opsTypeKey);
        if (linuxOps == null) {
            sendError(message, "8处理方法为空！");
            return;
        }
        ((Consumer<WebsocketMessage>) linuxOps).accept(message);
    }

    public void linuxConnect(WebsocketMessage message) {
        if (message == null) {
            return;
        }
        ConnectInfo connectInfo = message.getConnectInfo();
        if (connectInfo == null) {
            throw new ServiceException("连接信息为空！-{}", message);
        }
        String host = connectInfo.getHost();
        String user = connectInfo.getUser();
        String password = connectInfo.getPassword();
        Boolean blankLeastone = StringUtil.isBlankLeastone(host, user, password);
        if (blankLeastone) {
            throw new ServiceException("参数错误！-{}", message);
        }
        Integer remotePort = connectInfo.getRemotePort();
        connectInfo.setRemotePort(remotePort == null ? 22 : remotePort);
        createSession(connectInfo);
//        String connectionUuid = UUID.randomUUID().toString();
        String connectionUuid = message.getConnectionUuid();
        connectionUuidMap.put(connectionUuid, connectInfo);
        WebsocketResponse response = new WebsocketResponse();
        response.setConnectionUuid(connectionUuid);
        response.setConnectInfo(connectInfo);
        response.setOpsType(OpsTypeEnum.CONNECT.getKey());
        clientByNetty.sendMessage(response);
    }

    public ConnectInfo getConnectionInfoByUuid(String uuid) {
        return connectionUuidMap.get(uuid);
    }

    public void dealNormalCommand(WebsocketMessage message) {
        if (message == null) {
            return;
        }
        String connectionUuid = message.getConnectionUuid();
        if (StringUtils.isBlank(connectionUuid)) {
            clientByNetty.sendMessage(WebsocketResponse.ofData("连接uuid不能为空！-【" + JsonUtil.toString(message) + "】"));
            return;
        }
        if (getConnectionInfoByUuid(connectionUuid) == null) {
            clientByNetty.sendMessage(WebsocketResponse.ofData("未建立连接！-【" + JsonUtil.toString(message) + "】"));
            return;
        }
        ConnectInfo connectionInfo = getConnectionInfoByUuid(connectionUuid);
        connectionInfo.setRequestNum(message.getRequestNum());
        message.setConnectInfo(connectionInfo);
        String command = message.getCommand();
        if (StringUtils.isBlank(command)) {
            LogUtil.error("空指令！-{}", message);
            return;
        }
        requestNumConnectUuidMap.put(message.getRequestNum(), connectionUuid);
        operateWithShellChannel(message);

    }

    public void opsCommandThread(CommandOpsWrapper commandOpsWrapper, ResultOpsThread resultOpsThread, WebsocketMessage websocketMessage) {
        String command = websocketMessage.getCommand();
        if (StringUtils.isBlank(command)) {
            return;
        }
        commandOpsWrapper.operate(command);
    }

    public void opsResultThread(ResultOpsThread resultOpsThread) {

    }

    public void operateWithShellChannel(WebsocketMessage websocketMessage) {
        ConnectInfo connectInfo = websocketMessage.getConnectInfo();
        Session session = createSession(connectInfo);
        if (session == null) {
            throw new ServiceException("未建立连接！-{}", connectInfo);
        }
        String requestNum = connectInfo.getRequestNum();
        CommandOpsWrapper commandOpsWrapper = requestCommandOpsWrapperMap.get(requestNum);
        ResultOpsThread resultOpsThread = requestResultOpsThreadMap.get(requestNum);
        if (commandOpsWrapper != null && resultOpsThread != null) {
            opsCommandThread(commandOpsWrapper, resultOpsThread, websocketMessage);
            return;
        }
        Channel channel = null;
        try {
            channel = session.openChannel("shell");
        } catch (JSchException e) {
            throw new ServiceException(e, "创建jsch shell channel失败！");
        }
        ((ChannelShell) channel).setPtyType("dumb");
//        ((ChannelShell) channel).setTerminalMode("dumb");

        ((ChannelShell) channel).setPty(true);
        BufferedReader br = null;
        PrintStream commander = null;
        try {
            br = new BufferedReader(new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8.displayName()));
            commander = new PrintStream(channel.getOutputStream(), true, StandardCharsets.UTF_8.displayName());
            channel.connect();
            //存入sesssion map>>
            sessionRequestNumMap.computeIfAbsent(session, key -> CollcUtil.ofHashSet());
            sessionRequestNumMap.get(session).add(requestNum);
            //存入sesssion map<<
            CommandOpsWrapper newCommandOpsWrapper = new CommandOpsWrapper(requestNum, commander);
            ResultOpsThread newResultOpsThread = new ResultOpsThread(requestNum, br);
            newCommandOpsWrapper.setResultOpsThread(newResultOpsThread);
            requestCommandOpsWrapperMap.put(requestNum, newCommandOpsWrapper);
            requestResultOpsThreadMap.put(requestNum, newResultOpsThread);
//            Future commandFuture = threadExecutor.futureSubmit(newCommandOpsWrapper);
            Future resultFuture = threadExecutor.futureSubmit(newResultOpsThread);
            opsCommandThread(newCommandOpsWrapper, newResultOpsThread, websocketMessage);
//            commandFuture.get();
//            synchronized (this) {
//                this.wait();
//            }
            sessionChannelMap.computeIfAbsent(session, ss -> CollcUtil.ofHashSet());
            sessionChannelMap.get(session).add(channel);
            System.out.println("after ops");
        } catch (Exception e) {
            try {
                if (channel != null) {
                    channel.disconnect();
                }
                if (br != null) {
                    br.close();
                }
                if (commander != null) {
                    commander.close();
                }
            } catch (Exception ex) {
                LogUtil.error(ex, "关闭流出错！");
            }
            requestCommandOpsWrapperMap.remove(requestNum);
            requestResultOpsThreadMap.remove(requestNum);
            throw new ServiceException(e, "执行jsch shell操作失败！");
        }
    }

//    public void executeCommandWithAuth(InputStream argIn) {
//
//        Channel channel = null;
//        InputStream in = null;
//        InputStream er = null;
//        try {
//
//
//            //+++++++++++++++++++++++++++
//            channel = session.openChannel("shell");
//            OutputStream outputStream = channel.getOutputStream();
//            PrintStream commander = new PrintStream(channel.getOutputStream(), true, StandardCharsets.UTF_8.displayName());
//            channel.connect();
//
//            InputStream inputStream = channel.getInputStream();
//            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8.displayName()));
//            new Thread(() -> {
//                String line;
//                StringBuilder result = new StringBuilder();
//                while (true) {
////                    System.out.println("+++++in res loop++++++");
//                    try {
//                        if (((line = br.readLine()) != null)) {
//                            System.out.println(line);
//                        }
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
////                result.append(line).append("\r\n");
//                }
//            }).start();
//
//            BufferedReader argBr = new BufferedReader(new InputStreamReader(argIn, StandardCharsets.UTF_8.displayName()));
//            String line;
//            StringBuilder result = new StringBuilder();
//            while (true) {
////                System.out.println("+++++in args loop++++++");
//                try {
//                    if (((line = argBr.readLine()) != null)) {
//                        System.out.println(line);
//                        commander.println(line);
//                        if ("exit".equals(line)) {
//                            commander.close();
//                            channel.disconnect();
//                        }
//                    }
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
////                result.append(line).append("\r\n");
//            }
//            //            commander.println("grep -A 1 -B 1 '进入' /applog/dcos/dcos_gp19epmms_f8695b1610d84146bf3e51b64126636b/omo-server/omo_client_info_2021-12-01_0.log");
//            // the important command
////            commander.println("exit");
////            commander.close();
////            System.out.println("end");
//
//            //+++++++++++++++++++++++++++
//
////            channel = session.openChannel("shell");
//////            ((ChannelExec) channel).setPty(false);
//////            ((ChannelExec) channel).setCommand("tail -n 1000 /applog/dcos/dcos_gp19epmms_f8695b1610d84146bf3e51b64126636b/omo-server/omo_client_info_2021-12-01_0.log");
////            ((ChannelExec) channel).setCommand("grep -A 10 -B 5 '进入' /applog/dcos/dcos_gp19epmms_f8695b1610d84146bf3e51b64126636b/omo-server/omo_client_info_2021-12-01_0.log");
////
////            // get I/O streams
////
////            in = channel.getInputStream();
////            er = ((ChannelExec) channel).getErrStream();
////            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
////            BufferedReader errorReader = new BufferedReader(new InputStreamReader(er, StandardCharsets.UTF_8));
////
////            Thread thread = Thread.currentThread();
////
////            channel.connect();
////            String buf;
////            StringBuilder resBuilder = new StringBuilder();
////            while ((buf = reader.readLine()) != null) {
////                resBuilder.append("\r\n");
////                resBuilder.append(buf);
////            }
////            System.out.println(resBuilder.toString());
////            String errbuf;
////            StringBuilder errBuilder = new StringBuilder();
////            while ((errbuf = errorReader.readLine()) != null) {
////                errBuilder.append(errbuf);
////            }
////            System.out.println("+++++++++++++++++");
////            System.out.println(errBuilder.toString());
////            //两分钟超时，无论什么代码，永久运行下去并不是我们期望的结果，
////            //加超时好处多多，至少能防止内存泄漏，也能符合我们的预期，程序结束，相关的命令也结束。
////            //如果程序是前台进程，不能break掉，那么可以使用nohup去启动，或者使用子shell，但外层我们的程序一定要能结束。
////            channel.disconnect();
////            session.disconnect();
//        } catch (Exception e) {
//            e.printStackTrace();
//        } finally {
//            try {
//                if (in != null) {
//                    in.close();
//                }
//                if (er != null) {
//                    er.close();
//                }
//            } catch (Exception e) {
//                //
//            }
//
//            if (channel != null) {
//                channel.disconnect();
//            }
//            if (session != null) {
//                session.disconnect();
//            }
//        }
//    }


}
