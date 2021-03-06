/**
 * Copyright 2016 [Agar]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.ycsb.client;

import com.yahoo.ycsb.ClientBlueprint;
import com.yahoo.ycsb.ClientException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.client.utils.ClientUtils;
import com.yahoo.ycsb.utils.liberasure.LonghairLib;
import com.yahoo.ycsb.utils.connection.MemcachedConnection;
import com.yahoo.ycsb.utils.connection.S3Connection;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/*
 Assumptions:
 - number of Amazon regions = k + m
 - there is one S3 bucket per Amazon region
 */

public class FixCacheClient extends ClientBlueprint {
    public static String S3_REGIONS_PROPERTIES = "s3.regions";
    public static String S3_ENDPOINTS_PROPERTY = "s3.endpoints";
    public static String S3_BUCKETS_PROPERTY = "s3.buckets";
    public static String MEMCACHED_PROPERTY = "memcached.servers";
    public static String LONGHAIR_K_PROPERTY = "longhair.k";
    public static String LONGHAIR_K_PROPERTY_DEFAULT = "2";
    public static String LONGHAIR_M_PROPERTY = "longhair.m";
    public static String LONGHAIR_M_PROPERTY_DEFAULT = "1";
    public static String EXECUTOR_THREADS_PROPERTY = "executor.threads";
    public static String EXECUTOR_THREADS_PROPERTY_DEFAULT = "5";
    protected static Logger logger = Logger.getLogger(FixCacheClient.class);
    protected static Map<String, AtomicInteger> cacheHits;
    protected static Map<String, AtomicInteger> cacheMisses;
    private Properties properties;
    private List<S3Connection> s3Connections;
    private List<MemcachedConnection> memConnections;
    private ExecutorService executor;

    // Assumption: one bucket per region (num regions = num endpoints = num buckets)
    private void initS3() {
        List<String> regions = Arrays.asList(properties.getProperty(S3_REGIONS_PROPERTIES).split("\\s*,\\s*"));
        List<String> endpoints = Arrays.asList(properties.getProperty(S3_ENDPOINTS_PROPERTY).split("\\s*,\\s*"));
        List<String> s3Buckets = Arrays.asList(properties.getProperty(S3_BUCKETS_PROPERTY).split("\\s*,\\s*"));
        if (s3Buckets.size() != endpoints.size() || endpoints.size() != regions.size())
            logger.error("Configuration error: #buckets = #regions = #endpoints");

        // establish S3 connections
        s3Connections = new ArrayList<S3Connection>();
        for (int i = 0; i < s3Buckets.size(); i++) {
            String bucket = s3Buckets.get(i);
            String region = regions.get(i);
            String endpoint = endpoints.get(i);
            try {
                S3Connection client = new S3Connection(s3Buckets.get(i), regions.get(i), endpoints.get(i));
                s3Connections.add(client);
                logger.debug("S3 connection " + i + " " + bucket + " " + region + " " + endpoint);
            } catch (ClientException e) {
                logger.error("Error connecting to " + s3Buckets.get(i));
            }
        }
    }

    private void initLonghair() {
        LonghairLib.k = Integer.valueOf(properties.getProperty(LONGHAIR_K_PROPERTY, LONGHAIR_K_PROPERTY_DEFAULT));
        LonghairLib.m = Integer.valueOf(properties.getProperty(LONGHAIR_M_PROPERTY, LONGHAIR_M_PROPERTY_DEFAULT));
        logger.debug("k: " + LonghairLib.k + " m: " + LonghairLib.m);

        // check k >= 0 and k < 256
        if (LonghairLib.k < 0 || LonghairLib.k >= 256) {
            logger.error("Invalid Longhair.k: k should be >= 0 and < 256.");
        }
        // check m >=0 and m <= 256 - k
        if (LonghairLib.m < 0 || LonghairLib.m > 256 - LonghairLib.k) {
            logger.error("Invalid Longhair.m: m should be >= 0 and <= 256 - k.");
        }

        // init longhair
        if (LonghairLib.Longhair.INSTANCE._cauchy_256_init(2) != 0) {
            logger.error("Error initializing longhair");
        }
    }

    private void initCache() throws ClientException {
        memConnections = new ArrayList<MemcachedConnection>();
        List<String> memHosts = Arrays.asList(properties.getProperty(MEMCACHED_PROPERTY).split("\\s*,\\s*"));

        for (String memHost : memHosts) {
            MemcachedConnection memConnection = new MemcachedConnection(memHost);
            memConnections.add(memConnection);
            logger.debug("Memcached connection " + memHost);
        }

        if (cacheHits == null) {
            cacheHits = new HashMap<String, AtomicInteger>();
            for (String memHost : memHosts)
                cacheHits.put(memHost, new AtomicInteger(0));
        }
        if (cacheMisses == null) {
            cacheMisses = new HashMap<String, AtomicInteger>();
            for (String memHost : memHosts)
                cacheMisses.put(memHost, new AtomicInteger(0));
        }
    }

    @Override
    public void init() throws ClientException {
        logger.debug("FixCacheClient.init() start");
        properties = getProperties();

        initS3();
        initLonghair();
        initCache();

        // executor service
        final int threadsNum = Integer.valueOf(properties.getProperty(EXECUTOR_THREADS_PROPERTY, EXECUTOR_THREADS_PROPERTY_DEFAULT));
        logger.debug("threads num: " + threadsNum);
        executor = Executors.newFixedThreadPool(threadsNum);

        logger.debug("FixCacheClient.init() end");
    }

    @Override
    public void cleanup() throws ClientException {
        executor.shutdown();
        for (MemcachedConnection memConnection : memConnections) {
            String host = memConnection.getHost();
            logger.error(host + " Hits: " + cacheHits.get(host) + " Misses: " + cacheMisses.get(host));
        }
    }

    @Override
    public void cleanupRead() {

    }

    private byte[] readBlock(String baseKey, int blockNum) throws InterruptedException {
        String blockKey = baseKey + blockNum;
        S3Connection s3Connection = s3Connections.get(blockNum);
        byte[] block = s3Connection.read(blockKey);
        logger.debug("ReadBlock " + blockNum + " " + blockKey + " " + ClientUtils.bytesToHash(block));
        return block;
    }

    private byte[] readFromBackend(final String key) {
        List<Future> tasks = new ArrayList<Future>();
        // read blocks in parallel
        CompletionService<byte[]> completionService = new ExecutorCompletionService<byte[]>(executor);
        for (int i = 0; i < LonghairLib.k + LonghairLib.m; i++) {
            final int blockNumFin = i;
            Future newTask = completionService.submit(new Callable<byte[]>() {
                @Override
                public byte[] call() throws Exception {
                    return readBlock(key, blockNumFin);
                }
            });
            tasks.add(newTask);
        }

        int success = 0;
        int errors = 0;
        Set<byte[]> blocks = new HashSet<byte[]>();
        while (success < LonghairLib.k) {
            try {
                Future<byte[]> resultFuture = completionService.take();
                byte[] block = resultFuture.get();
                if (block != null) {
                    blocks.add(block);
                    success++;
                } else
                    errors++;
            } catch (Exception e) {
                errors++;
                logger.debug("Exception reading block.");
            }
            if (errors > LonghairLib.m)
                break;
        }

        for (Future f : tasks) {
            f.cancel(true);
        }

        byte[] data = null;
        if (success >= LonghairLib.k) {
            data = LonghairLib.decode(blocks);
        }
        return data;
    }

    private byte[] readFromCache(String key, int keyNum) {
        int memConnectionId = keyNum % memConnections.size();
        MemcachedConnection memConnection = memConnections.get(memConnectionId);
        byte[] data = memConnection.read(key);
        if (data != null) {
            logger.info("Read CACHE " + key + " " + data.length + " bytes " + memConnection.getHost());
            cacheHits.get(memConnection.getHost()).incrementAndGet();
        } else {
            cacheMisses.get(memConnection.getHost()).incrementAndGet();
        }
        return data;
    }

    private void cacheData(String key, int keyNum, byte[] data) {
        int memConnectionId = keyNum % memConnections.size();
        MemcachedConnection memConnection = memConnections.get(memConnectionId);
        logger.debug("Cache " + key + " at " + memConnection.getHost());
        Status status = memConnection.insert(key, data);
        //if (status.equals(Status.OK) == false)
        //    logger.warn("Error caching data " + key);
        /*else
            logger.debug("Cached data " + key);*/
    }

    @Override
    public byte[] read(final String key, final int keyNum) {
        byte[] data = readFromCache(key, keyNum);
        if (data == null) {
            data = readFromBackend(key);
            if (data != null) {
                logger.info("Read BACKEND " + key + " " + data.length + " bytes "); // + ClientUtils.bytesToHash(data));
                final byte[] dataFin = data;
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        cacheData(key, keyNum, dataFin);
                    }
                });
            }
        }
        return data;
    }

    @Override
    public Status update(String key, byte[] value) {
        return null;
    }

    /* insert data (encoded or full) in S3 buckets */
    @Override
    public Status insert(String key, byte[] value) {
        return null;
    }

    @Override
    public Status delete(String key) {
        return null;
    }
}
