/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.alibaba.rocketmq.namesrv.kvconfig;

import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.protocol.body.KVTable;
import com.alibaba.rocketmq.namesrv.NamesrvController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * @author shijia.wxr
 * KV配置管理
   key-value配置管理，增删改查
   读取或变更NameServer的配置属性，加载NamesrvConfig中配置的配置文件到内存，
   此类一个亮点就是使用轻量级的非线程安全容器，再结合读写锁对资源读写进行保护。尽最大程度提高线程的并发度
 */
public class KVConfigManager {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.NamesrvLoggerName);


    private final NamesrvController namesrvController;

    /**
     * ReadWriteLock 读写锁
     * Lock lock = new ReentrantLock();  Lock接口以及对象，使用它，很优雅的控制了竞争资源的安全访问，但是这种锁不区分读写，称这种锁为普通锁。
     * 为了提高性能，Java提供了读写锁，在读的地方使用读锁，在写的地方使用写锁，灵活控制，如果没有写锁的情况下，读是无阻塞的,在一定程度上提高了程序的执行效率。
     * Java中读写锁有个接口java.util.concurrent.locks.ReadWriteLock，也有具体的实现ReentrantReadWriteLock，详细的API可以查看JavaAPI文档。
     * ReentrantReadWriteLock 和 ReentrantLock 不是继承关系，但都是基于 AbstractQueuedSynchronizer 来实现。
     * lock方法 是基于CAS 来实现的
     * 注意： 在同一线程中，持有读锁后，不能直接调用写锁的lock方法 ，否则会造成死锁。
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final HashMap<String/* Namespace */, HashMap<String/* Key */, String/* Value */>> configTable =
            new HashMap<String, HashMap<String, String>>();


    public KVConfigManager(NamesrvController namesrvController) {
        this.namesrvController = namesrvController;
    }


    /**
     *
     */
    public void load() {
        //从文件中读取内容
        String content = MixAll.file2String(this.namesrvController.getNamesrvConfig().getKvConfigPath());
        if (content != null) {
            KVConfigSerializeWrapper kvConfigSerializeWrapper =
                    KVConfigSerializeWrapper.fromJson(content, KVConfigSerializeWrapper.class);
            if (null != kvConfigSerializeWrapper) {
                this.configTable.putAll(kvConfigSerializeWrapper.getConfigTable());
                log.info("load KV config table OK");
            }
        }
    }


    public void putKVConfig(final String namespace, final String key, final String value) {
        try {
            /**
             * lockInterruptibly : 名词解释：
             * 1）如果当前线程未被中断，则获取锁。
             2）如果该锁没有被另一个线程保持，则获取该锁并立即返回，将锁的保持计数设置为 1。
             3）如果当前线程已经保持此锁，则将保持计数加 1，并且该方法立即返回。
             4）如果锁被另一个线程保持，则出于线程调度目的，禁用当前线程，并且在发生以下两种情况之一以
             前，该线程将一直处于休眠状态：
                    1）锁由当前线程获得；
                    2）其他某个线程中断当前线程。
             5）如果当前线程获得该锁，则将锁保持计数设置为 1。
             如果当前线程：
                1）在进入此方法时已经设置了该线程的中断状态；或者
                2）在等待获取锁的同时被中断。
                 则抛出 InterruptedException，并且清除当前线程的已中断状态。
             */

            this.lock.writeLock().lockInterruptibly();
            try {
                HashMap<String, String> kvTable = this.configTable.get(namespace);
                if (null == kvTable) {
                    kvTable = new HashMap<String, String>();
                    this.configTable.put(namespace, kvTable);
                    log.info("putKVConfig create new Namespace {}", namespace);
                }
                /**
                 * hashMap 的put方法，如果存在key的值，则put方法会返回旧值
                 */
                final String prev = kvTable.put(key, value);
                if (null != prev) {
                    log.info("putKVConfig update config item, Namespace: {} Key: {} Value: {}", //
                            namespace, key, value);
                } else {
                    log.info("putKVConfig create new config item, Namespace: {} Key: {} Value: {}", //
                            namespace, key, value);
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("putKVConfig InterruptedException", e);
        }

        this.persist();
    }

    public void persist() {
        try {
            //此处用了读锁，因为是读取configTable的内容
            this.lock.readLock().lockInterruptibly();
            try {
                /**
                 * 将所有的namespace+key+value 配置信息序列化，转成json字符串，然后存到文件中
                 */
                KVConfigSerializeWrapper kvConfigSerializeWrapper = new KVConfigSerializeWrapper();
                kvConfigSerializeWrapper.setConfigTable(this.configTable);

                String content = kvConfigSerializeWrapper.toJson();

                if (null != content) {
                    MixAll.string2File(content, this.namesrvController.getNamesrvConfig().getKvConfigPath());
                }
            } catch (IOException e) {
                log.error("persist kvconfig Exception, "
                        + this.namesrvController.getNamesrvConfig().getKvConfigPath(), e);
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("persist InterruptedException", e);
        }

    }

    public void deleteKVConfig(final String namespace, final String key) {
        try {
            this.lock.writeLock().lockInterruptibly();
            try {
                HashMap<String, String> kvTable = this.configTable.get(namespace);
                if (null != kvTable) {
                    String value = kvTable.remove(key);
                    log.info("deleteKVConfig delete a config item, Namespace: {} Key: {} Value: {}", //
                            namespace, key, value);
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("deleteKVConfig InterruptedException", e);
        }

        this.persist();
    }

    public byte[] getKVListByNamespace(final String namespace) {
        try {
            this.lock.readLock().lockInterruptibly();
            try {
                HashMap<String, String> kvTable = this.configTable.get(namespace);
                if (null != kvTable) {
                    KVTable table = new KVTable();
                    table.setTable(kvTable);
                    return table.encode();
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("getKVListByNamespace InterruptedException", e);
        }

        return null;
    }

    public String getKVConfig(final String namespace, final String key) {
        try {
            this.lock.readLock().lockInterruptibly();
            try {
                HashMap<String, String> kvTable = this.configTable.get(namespace);
                if (null != kvTable) {
                    return kvTable.get(key);
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("getKVConfig InterruptedException", e);
        }

        return null;
    }

    public void printAllPeriodically() {
        try {
            this.lock.readLock().lockInterruptibly();
            try {
                log.info("--------------------------------------------------------");

                {
                    log.info("configTable SIZE: {}", this.configTable.size());
                    Iterator<Entry<String, HashMap<String, String>>> it =
                            this.configTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, HashMap<String, String>> next = it.next();
                        Iterator<Entry<String, String>> itSub = next.getValue().entrySet().iterator();
                        while (itSub.hasNext()) {
                            Entry<String, String> nextSub = itSub.next();
                            log.info("configTable NS: {} Key: {} Value: {}", next.getKey(), nextSub.getKey(),
                                    nextSub.getValue());
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("printAllPeriodically InterruptedException", e);
        }
    }
}
