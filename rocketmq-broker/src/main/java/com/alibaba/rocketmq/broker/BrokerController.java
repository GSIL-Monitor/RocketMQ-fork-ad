/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.broker;

import com.alibaba.rocketmq.broker.client.*;
import com.alibaba.rocketmq.broker.client.net.Broker2Client;
import com.alibaba.rocketmq.broker.client.rebalance.RebalanceLockManager;
import com.alibaba.rocketmq.broker.filtersrv.FilterServerManager;
import com.alibaba.rocketmq.broker.latency.BrokerFastFailure;
import com.alibaba.rocketmq.broker.latency.BrokerFixedThreadPoolExecutor;
import com.alibaba.rocketmq.broker.longpolling.NotifyMessageArrivingListener;
import com.alibaba.rocketmq.broker.longpolling.PullRequestHoldService;
import com.alibaba.rocketmq.broker.mqtrace.ConsumeMessageHook;
import com.alibaba.rocketmq.broker.mqtrace.SendMessageHook;
import com.alibaba.rocketmq.broker.offset.ConsumerOffsetManager;
import com.alibaba.rocketmq.broker.out.BrokerOuterAPI;
import com.alibaba.rocketmq.broker.plugin.MessageStoreFactory;
import com.alibaba.rocketmq.broker.plugin.MessageStorePluginContext;
import com.alibaba.rocketmq.broker.processor.*;
import com.alibaba.rocketmq.broker.slave.SlaveSynchronize;
import com.alibaba.rocketmq.broker.subscription.SubscriptionGroupManager;
import com.alibaba.rocketmq.broker.topic.TopicConfigManager;
import com.alibaba.rocketmq.common.*;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.constant.PermName;
import com.alibaba.rocketmq.common.namesrv.RegisterBrokerResult;
import com.alibaba.rocketmq.common.protocol.RequestCode;
import com.alibaba.rocketmq.common.protocol.body.TopicConfigSerializeWrapper;
import com.alibaba.rocketmq.common.stats.MomentStatsItem;
import com.alibaba.rocketmq.remoting.RPCHook;
import com.alibaba.rocketmq.remoting.RemotingServer;
import com.alibaba.rocketmq.remoting.netty.*;
import com.alibaba.rocketmq.store.DefaultMessageStore;
import com.alibaba.rocketmq.store.MessageArrivingListener;
import com.alibaba.rocketmq.store.MessageStore;
import com.alibaba.rocketmq.store.config.BrokerRole;
import com.alibaba.rocketmq.store.config.MessageStoreConfig;
import com.alibaba.rocketmq.store.stats.BrokerStats;
import com.alibaba.rocketmq.store.stats.BrokerStatsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;


/**
 * @author shijia.wxr
 */
public class BrokerController {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.BrokerLoggerName);
    private static final Logger logProtection = LoggerFactory.getLogger(LoggerName.ProtectionLoggerName);
    private static final Logger logWaterMark = LoggerFactory.getLogger(LoggerName.WaterMarkLoggerName);
    // 服务器配置
    private final BrokerConfig brokerConfig;
    // 通信层配置
    private final NettyServerConfig nettyServerConfig;
    private final NettyClientConfig nettyClientConfig;
    // 存储层配置
    private final MessageStoreConfig messageStoreConfig;
    // 配置文件版本号
    private final DataVersion configDataVersion = new DataVersion();
    // 消费进度存储
    private final ConsumerOffsetManager consumerOffsetManager;
    // Consumer连接、订阅关系管理
    private final ConsumerManager consumerManager;
    // Producer连接管理
    private final ProducerManager producerManager;
    // 检测所有客户端连接
    private final ClientHousekeepingService clientHousekeepingService;
    private final PullMessageProcessor pullMessageProcessor;
    private final PullRequestHoldService pullRequestHoldService;
    private final MessageArrivingListener messageArrivingListener;
    // Broker主动调用Client
    private final Broker2Client broker2Client;
    // 订阅组配置管理
    private final SubscriptionGroupManager subscriptionGroupManager;
    // 订阅组内成员发生变化，立刻通知所有成员
    private final ConsumerIdsChangeListener consumerIdsChangeListener;
    // 管理队列的锁分配
    private final RebalanceLockManager rebalanceLockManager = new RebalanceLockManager();
    // Broker的通信层客户端
    private final BrokerOuterAPI brokerOuterAPI;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
            "BrokerControllerScheduledThread"));
    // Slave定期从Master同步信息
    private final SlaveSynchronize slaveSynchronize;
    // 对消息写入进行流控
    private final BlockingQueue<Runnable> sendThreadPoolQueue;
    // 对消息读取进行流控
    private final BlockingQueue<Runnable> pullThreadPoolQueue;
    // FilterServer管理
    private final FilterServerManager filterServerManager;
    private final BrokerStatsManager brokerStatsManager;
    private final List<SendMessageHook> sendMessageHookList = new ArrayList<SendMessageHook>();
    private final List<ConsumeMessageHook> consumeMessageHookList = new ArrayList<ConsumeMessageHook>();
    // 存储层对象
    private MessageStore messageStore;
    // 通信层对象
    private RemotingServer remotingServer;
    private RemotingServer fastRemotingServer;
    // Topic配置
    private TopicConfigManager topicConfigManager;
    // 处理发送消息线程池
    private ExecutorService sendMessageExecutor;
    // 处理拉取消息线程池
    private ExecutorService pullMessageExecutor;
    // 处理管理Broker线程池
    private ExecutorService adminBrokerExecutor;
    // 处理管理Client线程池
    private ExecutorService clientManageExecutor;
    // 是否需要定期更新HA Master地址
    private boolean updateMasterHAServerAddrPeriodically = false;
    private BrokerStats brokerStats;
    private InetSocketAddress storeHost;
    private BrokerFastFailure brokerFastFailure;

    public BrokerController(//
                            final BrokerConfig brokerConfig, //
                            final NettyServerConfig nettyServerConfig, //
                            final NettyClientConfig nettyClientConfig, //
                            final MessageStoreConfig messageStoreConfig //
    ) {
        this.brokerConfig = brokerConfig;
        this.nettyServerConfig = nettyServerConfig;
        this.nettyClientConfig = nettyClientConfig;
        this.messageStoreConfig = messageStoreConfig;
        this.consumerOffsetManager = new ConsumerOffsetManager(this);
        this.topicConfigManager = new TopicConfigManager(this);
        this.pullMessageProcessor = new PullMessageProcessor(this);
        this.pullRequestHoldService = new PullRequestHoldService(this);
        this.messageArrivingListener = new NotifyMessageArrivingListener(this.pullRequestHoldService);
        this.consumerIdsChangeListener = new DefaultConsumerIdsChangeListener(this);
        this.consumerManager = new ConsumerManager(this.consumerIdsChangeListener);
        this.producerManager = new ProducerManager();
        this.clientHousekeepingService = new ClientHousekeepingService(this);
        this.broker2Client = new Broker2Client(this);
        this.subscriptionGroupManager = new SubscriptionGroupManager(this);
        this.brokerOuterAPI = new BrokerOuterAPI(nettyClientConfig);
        this.filterServerManager = new FilterServerManager(this);

        if (this.brokerConfig.getNamesrvAddr() != null) {
            this.brokerOuterAPI.updateNameServerAddressList(this.brokerConfig.getNamesrvAddr());
            log.info("user specfied name server address: {}", this.brokerConfig.getNamesrvAddr());
        }

        this.slaveSynchronize = new SlaveSynchronize(this);

        this.sendThreadPoolQueue = new LinkedBlockingQueue<Runnable>(this.brokerConfig.getSendThreadPoolQueueCapacity());

        this.pullThreadPoolQueue = new LinkedBlockingQueue<Runnable>(this.brokerConfig.getPullThreadPoolQueueCapacity());

        this.brokerStatsManager = new BrokerStatsManager(this.brokerConfig.getBrokerClusterName());
        this.setStoreHost(new InetSocketAddress(this.getBrokerConfig().getBrokerIP1(), this.getNettyServerConfig().getListenPort()));

        this.brokerFastFailure = new BrokerFastFailure(this);
    }

    public BrokerConfig getBrokerConfig() {
        return brokerConfig;
    }

    public NettyServerConfig getNettyServerConfig() {
        return nettyServerConfig;
    }

    public BlockingQueue<Runnable> getPullThreadPoolQueue() {
        return pullThreadPoolQueue;
    }

    public boolean initialize() throws CloneNotSupportedException {
        boolean result = true;
        //加载 topics.json、 consumerOffset.json、 subscriptionGroup.json 文件，
        // 分别将各文件的数据存入 TopicConfigManager、 ConsumerOffsetManager、SubscriptionGroupManager 对象中
        result = result && this.topicConfigManager.load();

        result = result && this.consumerOffsetManager.load();
        result = result && this.subscriptionGroupManager.load();

        if (result) {
            try {
                //初始化 DefaultMessageStore 对象，该对象是应用层访问存储层的访问类；
                this.messageStore =
                        new DefaultMessageStore(this.messageStoreConfig, this.brokerStatsManager, this.messageArrivingListener,
                                this.brokerConfig);

                this.brokerStats = new BrokerStats((DefaultMessageStore) this.messageStore);
                //load plugin
                MessageStorePluginContext context = new MessageStorePluginContext(messageStoreConfig, brokerStatsManager, messageArrivingListener, brokerConfig);
                this.messageStore = MessageStoreFactory.build(context, this.messageStore);
            } catch (IOException e) {
                result = false;
                e.printStackTrace();
            }
        }
        //调用 DefaultMessageStore.load 加载数据, 加载本地消息数据
        result = result && this.messageStore.load();

        if (result) {
            // 初始化通信层
            this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.clientHousekeepingService);
            NettyServerConfig fastConfig = (NettyServerConfig) this.nettyServerConfig.clone();
            fastConfig.setListenPort(nettyServerConfig.getListenPort() - 2);
            // 初始化发送消息线程池（ sendMessageExecutor）、
            // 拉取消息线程池（pullMessageExecutor）、
            // 管理 Broker 线程池（ adminBrokerExecutor）、
            // 客户端管理线程池（ clientManageExecutor）
            this.fastRemotingServer = new NettyRemotingServer(fastConfig, this.clientHousekeepingService);
            this.sendMessageExecutor = new BrokerFixedThreadPoolExecutor(//
                    this.brokerConfig.getSendMessageThreadPoolNums(),//
                    this.brokerConfig.getSendMessageThreadPoolNums(),//
                    1000 * 60,//
                    TimeUnit.MILLISECONDS,//
                    this.sendThreadPoolQueue,//
                    new ThreadFactoryImpl("SendMessageThread_"));
7
            this.pullMessageExecutor = new BrokerFixedThreadPoolExecutor(//
                    this.brokerConfig.getPullMessageThreadPoolNums(),//
                    this.brokerConfig.getPullMessageThreadPoolNums(),//
                    1000 * 60,//
                    TimeUnit.MILLISECONDS,//
                    this.pullThreadPoolQueue,//
                    new ThreadFactoryImpl("PullMessageThread_"));

            this.adminBrokerExecutor =
                    Executors.newFixedThreadPool(this.brokerConfig.getAdminBrokerThreadPoolNums(), new ThreadFactoryImpl(
                            "AdminBrokerThread_"));

            this.clientManageExecutor =
                    Executors.newFixedThreadPool(this.brokerConfig.getClientManageThreadPoolNums(), new ThreadFactoryImpl(
                            "ClientManageThread_"));
            //注册事件处理器
            this.registerProcessor();


            // TODO remove in future
            // 启动定时任务,每天凌晨00:00:00统计消息量
            final long initialDelay = UtilAll.computNextMorningTimeMillis() - System.currentTimeMillis();
            final long period = 1000 * 60 * 60 * 24;
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrokerController.this.getBrokerStats().record();
                    } catch (Throwable e) {
                        log.error("schedule record error.", e);
                    }
                }
            }, initialDelay, period, TimeUnit.MILLISECONDS);

            // 定时刷消费进度
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrokerController.this.consumerOffsetManager.persist();
                    } catch (Throwable e) {
                        log.error("schedule persist consumerOffset error.", e);
                    }
                }
            }, 1000 * 10, this.brokerConfig.getFlushConsumerOffsetInterval(), TimeUnit.MILLISECONDS);


            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrokerController.this.protectBroker();
                    } catch (Exception e) {
                        log.error("protectBroker error.", e);
                    }
                }
            }, 3, 3, TimeUnit.MINUTES);

            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrokerController.this.printWaterMark();
                    } catch (Exception e) {
                        log.error("printWaterMark error.", e);
                    }
                }
            }, 10, 1, TimeUnit.SECONDS);

            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        log.info("dispatch behind commit log {} bytes", BrokerController.this.getMessageStore().dispatchBehindBytes());
                    } catch (Throwable e) {
                        log.error("schedule dispatchBehindBytes error.", e);
                    }
                }
            }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
            // 先获取Name Server地址
            if (this.brokerConfig.getNamesrvAddr() != null) {
                this.brokerOuterAPI.updateNameServerAddressList(this.brokerConfig.getNamesrvAddr());
            }
            //// 获取不到地址，则定时获取Name Server地址
            else if (this.brokerConfig.isFetchNamesrvAddrByAddressServer()) {
                this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            BrokerController.this.brokerOuterAPI.fetchNameServerAddr();
                        } catch (Throwable e) {
                            log.error("ScheduledTask fetchNameServerAddr exception", e);
                        }
                    }
                }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
            }

            //若该 Broker 为主用，则启动定时任务打印主用 Broker 和备用 Broker 在Commitlog 上的写入位置相差多少个字节
            //9、若该 Broker 为备用，设置同步 Config 文件的定时任务，每隔 60 秒调用 SlaveSynchronize.syncAll()方法向主用 Broker 请求一次 config 类文件的同步。
            if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
                if (this.messageStoreConfig.getHaMasterAddress() != null && this.messageStoreConfig.getHaMasterAddress().length() >= 6) {
                    this.messageStore.updateHaMasterAddress(this.messageStoreConfig.getHaMasterAddress());
                    this.updateMasterHAServerAddrPeriodically = false;
                } else {
                    this.updateMasterHAServerAddrPeriodically = true;
                }

                this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            BrokerController.this.slaveSynchronize.syncAll();
                        } catch (Throwable e) {
                            log.error("ScheduledTask syncAll slave exception", e);
                        }
                    }
                }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
            } else {
                this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            BrokerController.this.printMasterAndSlaveDiff();
                        } catch (Throwable e) {
                            log.error("schedule printMasterAndSlaveDiff error.", e);
                        }
                    }
                }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
            }
        }

        return result;
    }

    public void registerProcessor() {
        /**
         * SendMessageProcessor
         * 处理Producer发送的消息
         如果收到请求码为SEND_MESSAGE/SEND_MESSAGE_V2 的消息后转由 SendMessageProcessor 处理。大致过程如下：
         1、解码收到的消息，并构建 SendMessageRequestHeader 对象；
         2、若 SendMessageProcessor 处理器设置了发送消息的钩子，则调用该钩子类的
         sendMessageBefore 方法，执行发送前的处理方法，在发送消息结果之后调用
         sendMessageAfter 方法。 该钩子类是为了便于业务层面的扩展而设计的
         3、 调用 SendMessageProcessor.sendMessage方法处理的消息并返回处理结果
         PutMessageResult 对象；
         4、 根据上一步返回的 PutMessageResult 对象中的status，设置相应的消息代码
         */
        SendMessageProcessor sendProcessor = new SendMessageProcessor(this);
        sendProcessor.registerSendMessageHook(sendMessageHookList);
        sendProcessor.registerConsumeMessageHook(consumeMessageHookList);

        this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);
        /**
         * PullMessageProcessor
         */
        this.remotingServer.registerProcessor(RequestCode.PULL_MESSAGE, this.pullMessageProcessor, this.pullMessageExecutor);
        this.pullMessageProcessor.registerConsumeMessageHook(consumeMessageHookList);

        /**
         * QueryMessageProcessor
         */
        NettyRequestProcessor queryProcessor = new QueryMessageProcessor(this);
        this.remotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.pullMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.pullMessageExecutor);

        this.fastRemotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.pullMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.pullMessageExecutor);

        /**
         * ClientManageProcessor
         */
        ClientManageProcessor clientProcessor = new ClientManageProcessor(this);
        this.remotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.clientManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, clientProcessor, this.clientManageExecutor);


        this.fastRemotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.clientManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, clientProcessor, this.clientManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, clientProcessor, this.clientManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, clientProcessor, this.clientManageExecutor);

        this.fastRemotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, clientProcessor, this.clientManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, clientProcessor, this.clientManageExecutor);

        /**
         * EndTransactionProcessor
         */
        this.remotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.sendMessageExecutor);

        /**
         * Default
         */
        AdminBrokerProcessor adminProcessor = new AdminBrokerProcessor(this);
        this.remotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
        this.fastRemotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
    }

    public BrokerStats getBrokerStats() {
        return brokerStats;
    }

    public void setBrokerStats(BrokerStats brokerStats) {
        this.brokerStats = brokerStats;
    }

    public void protectBroker() {
        if (this.brokerConfig.isDisableConsumeIfConsumerReadSlowly()) {
            final Iterator<Map.Entry<String, MomentStatsItem>> it = this.brokerStatsManager.getMomentStatsItemSetFallSize().getStatsItemTable().entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, MomentStatsItem> next = it.next();
                final long fallBehindBytes = next.getValue().getValue().get();
                if (fallBehindBytes > this.brokerConfig.getConsumerFallbehindThreshold()) {
                    final String[] split = next.getValue().getStatsKey().split("@");
                    final String group = split[2];
                    logProtection.info("[PROTECT_BROKER] the consumer[{}] consume slowly, {} bytes, disable it", group, fallBehindBytes);
                    this.subscriptionGroupManager.disableConsume(group);
                }
            }
        }
    }

    public long headSlowTimeMills(BlockingQueue<Runnable> q) {
        long slowTimeMills = 0;
        final Runnable peek = q.peek();
        if (peek != null) {
            RequestTask rt = BrokerFastFailure.castRunnable(peek);
            slowTimeMills = this.messageStore.now() - rt.getCreateTimestamp();
        }

        if (slowTimeMills < 0) slowTimeMills = 0;

        return slowTimeMills;
    }

    public long headSlowTimeMills4SendThreadPoolQueue() {
        return this.headSlowTimeMills(this.sendThreadPoolQueue);
    }

    public long headSlowTimeMills4PullThreadPoolQueue() {
        return this.headSlowTimeMills(this.pullThreadPoolQueue);
    }

    public void printWaterMark() {
        logWaterMark.info("[WATERMARK] Send Queue Size: {} SlowTimeMills: {}", this.sendThreadPoolQueue.size(), headSlowTimeMills4SendThreadPoolQueue());
        logWaterMark.info("[WATERMARK] Pull Queue Size: {} SlowTimeMills: {}", this.pullThreadPoolQueue.size(), headSlowTimeMills4PullThreadPoolQueue());
    }

    public MessageStore getMessageStore() {
        return messageStore;
    }

    public void setMessageStore(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    private void printMasterAndSlaveDiff() {
        long diff = this.messageStore.slaveFallBehindMuch();

        // XXX: warn and notify me
        log.info("slave fall behind master, how much, {} bytes", diff);
    }

    public Broker2Client getBroker2Client() {
        return broker2Client;
    }

    public String getConfigDataVersion() {
        return this.configDataVersion.toJson();
    }

    public ConsumerManager getConsumerManager() {
        return consumerManager;
    }

    public ConsumerOffsetManager getConsumerOffsetManager() {
        return consumerOffsetManager;
    }

    public MessageStoreConfig getMessageStoreConfig() {
        return messageStoreConfig;
    }

    public ProducerManager getProducerManager() {
        return producerManager;
    }

    public void setFastRemotingServer(RemotingServer fastRemotingServer) {
        this.fastRemotingServer = fastRemotingServer;
    }

    public PullMessageProcessor getPullMessageProcessor() {
        return pullMessageProcessor;
    }

    public PullRequestHoldService getPullRequestHoldService() {
        return pullRequestHoldService;
    }

    public SubscriptionGroupManager getSubscriptionGroupManager() {
        return subscriptionGroupManager;
    }

    public void shutdown() {
        if (this.brokerStatsManager != null) {
            this.brokerStatsManager.shutdown();
        }

        if (this.clientHousekeepingService != null) {
            this.clientHousekeepingService.shutdown();
        }

        if (this.pullRequestHoldService != null) {
            this.pullRequestHoldService.shutdown();
        }

        if (this.remotingServer != null) {
            this.remotingServer.shutdown();
        }

        if (this.fastRemotingServer != null) {
            this.fastRemotingServer.shutdown();
        }

        if (this.messageStore != null) {
            this.messageStore.shutdown();
        }

        this.scheduledExecutorService.shutdown();
        try {
            this.scheduledExecutorService.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }

        this.unregisterBrokerAll();

        if (this.sendMessageExecutor != null) {
            this.sendMessageExecutor.shutdown();
        }

        if (this.pullMessageExecutor != null) {
            this.pullMessageExecutor.shutdown();
        }

        if (this.adminBrokerExecutor != null) {
            this.adminBrokerExecutor.shutdown();
        }

        if (this.brokerOuterAPI != null) {
            this.brokerOuterAPI.shutdown();
        }

        this.consumerOffsetManager.persist();

        if (this.filterServerManager != null) {
            this.filterServerManager.shutdown();
        }

        if (this.brokerFastFailure != null) {
            this.brokerFastFailure.shutdown();
        }
    }

    private void unregisterBrokerAll() {
        this.brokerOuterAPI.unregisterBrokerAll(//
                this.brokerConfig.getBrokerClusterName(), //
                this.getBrokerAddr(), //
                this.brokerConfig.getBrokerName(), //
                this.brokerConfig.getBrokerId());
    }

    public String getBrokerAddr() {
        String addr = this.brokerConfig.getBrokerIP1() + ":" + this.nettyServerConfig.getListenPort();
        return addr;
    }

    public void start() throws Exception {
        //调用 DefaultMessageStore.start 方法启动 DefaultMessageStore 对象中的一些服务线程。
        if (this.messageStore != null) {
            this.messageStore.start();
        }

        if (this.remotingServer != null) {
            this.remotingServer.start();
        }

        if (this.fastRemotingServer != null) {
            this.fastRemotingServer.start();
        }

        //启动 BrokerOuterAPI 中的 NettyRemotingClient，即建立与 NameServer的链接，用于自身 Broker 与其他模块的 RPC 功能调用；
        if (this.brokerOuterAPI != null) {
            this.brokerOuterAPI.start();
        }

        //启动拉消息管理服务 PullRequestHoldService，当拉取消息时未发现消息，则初始化 PullRequeset 对象放入该服务线程的 pullRequestTable 列表中，
        // 由 PullRequestHoldService 每隔 1 秒钟就检查一遍每个 PullRequeset 对象要读取的数据位置在 consumequeue 中是否已经有数据了，
        // 若有则交由PullManageProcessor 处理。
        if (this.pullRequestHoldService != null) {
            this.pullRequestHoldService.start();
        }

        if (this.clientHousekeepingService != null) {
            this.clientHousekeepingService.start();
        }

        //启动 FilterServerManager，每隔 30 秒定期一次检查 Filter Server 个数，若没有达到 16 个则创建
        if (this.filterServerManager != null) {
            this.filterServerManager.start();
        }

        //首先调用 BrokerController.registerBrokerAll 方法立即向 NameServer注册 Broker；然后设置定时任务，每隔 30 秒调用一次该方法向 NameServer 注册；
        this.registerBrokerAll(true, false);


        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    BrokerController.this.registerBrokerAll(true, false);
                } catch (Throwable e) {
                    log.error("registerBrokerAll Exception", e);
                }
            }
        }, 1000 * 10, 1000 * 30, TimeUnit.MILLISECONDS);


        if (this.brokerStatsManager != null) {
            this.brokerStatsManager.start();
        }

        if (this.brokerFastFailure != null) {
            this.brokerFastFailure.start();
        }
    }

    //（1）将 TopicConfigManager.topicConfigTable 变量序列化成TopicConfigSerializeWrapper 对象；
    //（2）通过BrokerOuterAPI.registerBroker向NameServer注册
    //（3）根据 updateMasterHAServerAddrPeriodically 标注位判断是否需要更新主用Broker地址
    //（4）用 NameServer 返回的 MasterAddr 值更新 SlaveSynchronize.masterAddr值，用于主备 Broker 同步 Config 文件使用；
    //（5）根据 Name Server 上的配置参数 ORDER_TOPIC_CONFIG 的值来更新Broker端的 TopicConfigManager.topicConfigTable 变量值
    public synchronized void registerBrokerAll(final boolean checkOrderConfig, boolean oneway) {
        TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();

        if (!PermName.isWriteable(this.getBrokerConfig().getBrokerPermission())
                || !PermName.isReadable(this.getBrokerConfig().getBrokerPermission())) {
            ConcurrentHashMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<String, TopicConfig>();
            for (TopicConfig topicConfig : topicConfigWrapper.getTopicConfigTable().values()) {
                TopicConfig tmp =
                        new TopicConfig(topicConfig.getTopicName(), topicConfig.getReadQueueNums(), topicConfig.getWriteQueueNums(),
                                this.brokerConfig.getBrokerPermission());
                topicConfigTable.put(topicConfig.getTopicName(), tmp);
            }
            topicConfigWrapper.setTopicConfigTable(topicConfigTable);
        }

        RegisterBrokerResult registerBrokerResult = this.brokerOuterAPI.registerBrokerAll(//
                this.brokerConfig.getBrokerClusterName(), //
                this.getBrokerAddr(), //
                this.brokerConfig.getBrokerName(), //
                this.brokerConfig.getBrokerId(), //
                this.getHAServerAddr(), //
                topicConfigWrapper,//
                this.filterServerManager.buildNewFilterServerList(),//
                oneway,//
                this.brokerConfig.getRegisterBrokerTimeoutMills());

        if (registerBrokerResult != null) {
            if (this.updateMasterHAServerAddrPeriodically && registerBrokerResult.getHaServerAddr() != null) {
                this.messageStore.updateHaMasterAddress(registerBrokerResult.getHaServerAddr());
            }

            this.slaveSynchronize.setMasterAddr(registerBrokerResult.getMasterAddr());

            if (checkOrderConfig) {
                this.getTopicConfigManager().updateOrderTopicConfig(registerBrokerResult.getKvTable());
            }
        }
    }

    public TopicConfigManager getTopicConfigManager() {
        return topicConfigManager;
    }

    public void setTopicConfigManager(TopicConfigManager topicConfigManager) {
        this.topicConfigManager = topicConfigManager;
    }

    public String getHAServerAddr() {
        String addr = this.brokerConfig.getBrokerIP2() + ":" + this.messageStoreConfig.getHaListenPort();
        return addr;
    }

    public void updateAllConfig(Properties properties) {
        MixAll.properties2Object(properties, brokerConfig);
        MixAll.properties2Object(properties, nettyServerConfig);
        MixAll.properties2Object(properties, nettyClientConfig);
        MixAll.properties2Object(properties, messageStoreConfig);
        this.configDataVersion.nextVersion();
        this.flushAllConfig();
    }

    private void flushAllConfig() {
        String allConfig = this.encodeAllConfig();
        try {
            MixAll.string2File(allConfig, BrokerPathConfigHelper.getBrokerConfigPath());
            log.info("flush broker config, {} OK", BrokerPathConfigHelper.getBrokerConfigPath());
        } catch (IOException e) {
            log.info("flush broker config Exception, " + BrokerPathConfigHelper.getBrokerConfigPath(), e);
        }
    }

    public String encodeAllConfig() {
        StringBuilder sb = new StringBuilder();
        {
            Properties properties = MixAll.object2Properties(this.brokerConfig);
            if (properties != null) {
                sb.append(MixAll.properties2String(properties));
            } else {
                log.error("encodeAllConfig object2Properties error");
            }
        }

        {
            Properties properties = MixAll.object2Properties(this.messageStoreConfig);
            if (properties != null) {
                sb.append(MixAll.properties2String(properties));
            } else {
                log.error("encodeAllConfig object2Properties error");
            }
        }

        {
            Properties properties = MixAll.object2Properties(this.nettyServerConfig);
            if (properties != null) {
                sb.append(MixAll.properties2String(properties));
            } else {
                log.error("encodeAllConfig object2Properties error");
            }
        }

        {
            Properties properties = MixAll.object2Properties(this.nettyClientConfig);
            if (properties != null) {
                sb.append(MixAll.properties2String(properties));
            } else {
                log.error("encodeAllConfig object2Properties error");
            }
        }
        return sb.toString();
    }

    public RebalanceLockManager getRebalanceLockManager() {
        return rebalanceLockManager;
    }

    public SlaveSynchronize getSlaveSynchronize() {
        return slaveSynchronize;
    }

    public ExecutorService getPullMessageExecutor() {
        return pullMessageExecutor;
    }

    public void setPullMessageExecutor(ExecutorService pullMessageExecutor) {
        this.pullMessageExecutor = pullMessageExecutor;
    }

    public BlockingQueue<Runnable> getSendThreadPoolQueue() {
        return sendThreadPoolQueue;
    }

    public FilterServerManager getFilterServerManager() {
        return filterServerManager;
    }

    public BrokerStatsManager getBrokerStatsManager() {
        return brokerStatsManager;
    }

    public List<SendMessageHook> getSendMessageHookList() {
        return sendMessageHookList;
    }

    public void registerSendMessageHook(final SendMessageHook hook) {
        this.sendMessageHookList.add(hook);
        log.info("register SendMessageHook Hook, {}", hook.hookName());
    }

    public List<ConsumeMessageHook> getConsumeMessageHookList() {
        return consumeMessageHookList;
    }

    public void registerConsumeMessageHook(final ConsumeMessageHook hook) {
        this.consumeMessageHookList.add(hook);
        log.info("register ConsumeMessageHook Hook, {}", hook.hookName());
    }

    public void registerServerRPCHook(RPCHook rpcHook) {
        getRemotingServer().registerRPCHook(rpcHook);
    }

    public RemotingServer getRemotingServer() {
        return remotingServer;
    }

    public void setRemotingServer(RemotingServer remotingServer) {
        this.remotingServer = remotingServer;
    }

    public void registerClientRPCHook(RPCHook rpcHook) {
        this.getBrokerOuterAPI().registerRPCHook(rpcHook);
    }

    public BrokerOuterAPI getBrokerOuterAPI() {
        return brokerOuterAPI;
    }

    public InetSocketAddress getStoreHost() {
        return storeHost;
    }

    public void setStoreHost(InetSocketAddress storeHost) {
        this.storeHost = storeHost;
    }
}
