package com.yahoo.ycsb.dual.clients;

import com.yahoo.ycsb.ClientBlueprint;
import com.yahoo.ycsb.ClientException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.common.communication.ProxyReply;
import com.yahoo.ycsb.common.liberasure.LonghairLib;
import com.yahoo.ycsb.common.memcached.MemcachedConnection;
import com.yahoo.ycsb.dual.connections.ProxyConnection;
import com.yahoo.ycsb.dual.connections.S3Connection;
import com.yahoo.ycsb.dual.utils.ECBlock;
import com.yahoo.ycsb.dual.utils.PropertyFactory;
import com.yahoo.ycsb.dual.utils.Storage;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yahoo.ycsb.dual.utils.PropertyFactory.*;

/*
  IntelliJ
  Main: com.yahoo.ycsb.Client
  VM options: -Xmx3g
  Program arguments: -client com.yahoo.ycsb.dual.clients.GreedyCacheClient -p fieldlength=4194304 -P workloads/myworkload
  Working directory: /home/ubuntu/work/repos/YCSB
  Use classpath of module: root
  JRE: 1.8
*/

/*
   Command line:
   cd YCSB
   mvn clean package
   bin/ycsb proxy -p cachesize=40 -P workloads/myworkload
   bin/ycsb run greedy -threads 2 -p fieldlength=4194304 -P workloads/myworkload
*/

public class DynamicCacheClient extends ClientBlueprint {
    public static Logger logger = Logger.getLogger(DynamicCacheClient.class);

    public static AtomicInteger cacheHits;
    public static AtomicInteger cachePartialHits;
    public static AtomicInteger cacheMisses;

    public static PropertyFactory propertyFactory;

    private List<S3Connection> s3Connections;
    private MemcachedConnection memConnection;
    private ProxyConnection proxyConnection;
    private List<String> s3Buckets;
    private ExecutorService executor; // one executor per client thread

    private void initS3() {
        List<String> regions = Arrays.asList(propertyFactory.propertiesMap.get(S3_REGIONS_PROPERTY).split("\\s*,\\s*"));
        List<String> endpoints = Arrays.asList(propertiesMap.get(S3_ENDPOINTS_PROPERTY).split("\\s*,\\s*"));
        s3Buckets = Arrays.asList(propertyFactory.propertiesMap.get(S3_BUCKETS_PROPERTY).split("\\s*,\\s*"));
        if (s3Buckets.size() != endpoints.size() || endpoints.size() != regions.size())
            logger.error("Configuration error: #buckets = #regions = #endpoints");

        s3Connections = new ArrayList<>();
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
        LonghairLib.k = Integer.valueOf(propertyFactory.propertiesMap.get(LONGHAIR_K_PROPERTY));
        LonghairLib.m = Integer.valueOf(propertyFactory.propertiesMap.get(LONGHAIR_M_PROPERTY));
        logger.debug("k: " + LonghairLib.k + " m: " + LonghairLib.m);

        // check k >= 0 and k < 256
        if (LonghairLib.k < 0 || LonghairLib.k >= 256) {
            logger.error("Invalid Longhair.k: k should be >= 0 and < 256.");
        }
        // check m >=0 and m <= 256 - k
        if (LonghairLib.m < 0 || LonghairLib.m > 256 - LonghairLib.k) {
            logger.error("Invalid Longhair.m: m should be >= 0 and <= 256 - k.");
        }
        if (LonghairLib.Longhair.INSTANCE._cauchy_256_init(2) != 0) {
            logger.error("Error initializing longhair");
        }
    }

    private void initCache() throws ClientException {
        // connection to closest proxy
        proxyConnection = new ProxyConnection(getProperties());

        // connection to closest memcached server
        String memHost = propertyFactory.propertiesMap.get(MEMCACHED_SERVER_PROPERTY);
        memConnection = new MemcachedConnection(memHost);
        logger.debug("Memcached connection " + memHost);
    }

    public void init() throws ClientException {
        logger.debug("SmartCacheClient.init() start");

        // init counters for stats
        if (cacheHits == null)
            cacheHits = new AtomicInteger(0);
        if (cacheMisses == null)
            cacheMisses = new AtomicInteger(0);
        if (cachePartialHits == null)
            cachePartialHits = new AtomicInteger(0);

        propertyFactory = new PropertyFactory(getProperties());
        initS3();
        initLonghair();
        initCache();

        if (executor == null) {
            final int threadsNum = Integer.valueOf(propertyFactory.propertiesMap.get(EXECUTOR_THREADS_PROPERTY));
            logger.debug("threads num: " + threadsNum);
            executor = Executors.newFixedThreadPool(threadsNum);
        }
        logger.debug("SmartCacheClient.init() end");
    }


    @Override
    public void cleanup() throws ClientException {
        logger.error("Hits: " + cacheHits + " Misses: " + cacheMisses + " PartialHits: " + cachePartialHits);
        if (executor.isTerminated())
            executor.shutdown();
    }

    /**
     * Compute which blocks are stored in the given region
     *
     * @param region name of region
     * @return list of block ids stored in that region
     */
    private List<Integer> getBlockIdsByRegion(String region) {
        List<Integer> blockIds = new ArrayList<>();

        // identify connection id corresponding to region
        int s3connId = Integer.MIN_VALUE;
        for (int i = 0; i < s3Connections.size(); i++) {
            String name = s3Connections.get(i).getRegion();
            if (name.equals(region)) {
                s3connId = i;
                break;
            }
        }
        if (s3connId == Integer.MIN_VALUE)
            logger.error("Invalid s3 connection id!");

        // compute block ids
        int s3ConnNum = s3Connections.size();
        int blocksPerRegion = (LonghairLib.k + LonghairLib.m) / s3ConnNum;
        for (int i = 0; i < blocksPerRegion; i++) {
            int blockId = i * s3ConnNum + s3connId;
            blockIds.add(blockId);
        }
        logger.info(region + " " + blockIds);
        return blockIds;
    }

    /**
     * Read a block from the backend
     * @param key base key of data the block is part of
     * @param blockId id of the block within the data
     * @return the block
     */
    private ECBlock readBackendBlock(String key, int blockId) {
        String blockKey = key + blockId;
        //return new ECBlock(blockId, blockKey, new byte[699072], Storage.BACKEND);
        int s3ConnNum = blockId % s3Connections.size();
        S3Connection s3Connection = s3Connections.get(s3ConnNum);
        byte[] bytes = s3Connection.read(blockKey);

        ECBlock ecblock = null;
        if (bytes != null)
            ecblock = new ECBlock(key, blockId, bytes, Storage.BACKEND);
        else
            logger.error("[Error] ReadBlockBackend " + key + " block" + blockId + " from bucket " + s3Buckets.get(s3ConnNum));

        return ecblock;
    }

    /**
     * Read blocks from backend, according to background recipe from proxy
     * @param key base key of the data blocks are part of
     * @param backendRecipe which blocks should be read from backend, according to proxy
     * @return list of blocks
     */
    public List<ECBlock> readBackend(final String key, List<String> backendRecipe) {
        List<ECBlock> ecblocks = new ArrayList<>();

        int targetBlocksNum = 0;
        CompletionService<ECBlock> completionService = new ExecutorCompletionService<>(executor);

        // for each region
        for (final String region : backendRecipe) {
            // compute block ids
            List<Integer> blockIds = getBlockIdsByRegion(region);
            targetBlocksNum += blockIds.size();

            // try to read each block
            for (Integer blockId : blockIds) {
                final int blockIdFin = blockId;
                completionService.submit(new Callable<ECBlock>() {
                    @Override
                    public ECBlock call() throws Exception {
                        return readBackendBlock(key, blockIdFin);
                    }
                });
            }
        }

        // wait to receive block num blocks
        int success = 0;
        int errors = 0;
        while (success + errors < targetBlocksNum) {
            try {
                Future<ECBlock> resultFuture = completionService.take();
                ECBlock ecblock = resultFuture.get();
                if (ecblock != null) {
                    ecblocks.add(ecblock);
                    success++;
                } else
                    errors++;
            } catch (Exception e) {
                errors++;
                logger.warn("Error reading block for " + key);
            }
        }

        return ecblocks;
    }

    /**
     * Store a block in cache
     *
     * @param ecblock block to be cached
     */
    private void cacheBlock(ECBlock ecblock) {
        String key = ecblock.getBaseKey();
        Status status = memConnection.insert(ecblock.getBaseKey(), ecblock.getBytes());
        if (status == Status.OK)
            logger.debug("Cache  " + key + " block " + ecblock.getId() + " at " + memConnection.getHost());
        else
            logger.debug("[Error] Cache  " + key + " block " + ecblock.getId() + " at " + memConnection.getHost());
    }

    /**
     * Read block from cache; if it is not possible, fall back to the backend and cache the block in the background
     * @param key base key of data the block is part of
     * @param blockId id of block to read
     * @return block (bytes of encoded data + where it was read from: cache or backend)
     */
    private ECBlock readCacheBlock(final String key, int blockId) {
        String blockKey = key + blockId;
        byte[] bytes = memConnection.read(blockKey);

        ECBlock ecblock = null;
        // try to read block from cache
        if (bytes != null) {
            logger.debug("CacheHit " + key + " block " + blockId);
            ecblock = new ECBlock(key, blockId, bytes, Storage.CACHE);
        } else {
            // if not possible, read block from backend
            logger.debug("CacheMiss " + key + " block " + blockId);
            ecblock = readBackendBlock(key, blockId);

            // cache block in the background
            final ECBlock ecblockFin = ecblock;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    cacheBlock(ecblockFin);
                }
            });
        }
        return ecblock;
    }

    /**
     * Read blocks from cache in parallel, according to cache recipe
     * @param key base key of data to be read
     * @param cacheRecipe which blocks are supposed to be in the cache (identified by region)
     * @return list of blocks
     */
    private List<ECBlock> readCache(final String key, List<String> cacheRecipe) {
        List<ECBlock> ecblocks = new ArrayList<ECBlock>();

        int targetBlocksNum = 0;
        CompletionService<ECBlock> completionService = new ExecutorCompletionService<ECBlock>(executor);
        // for each region in cache recipe
        for (final String region : cacheRecipe) {
            // compute block ids
            List<Integer> blockIds = getBlockIdsByRegion(region);
            targetBlocksNum += blockIds.size();

            // try to read each block from cache; if not possible, readCache falls back to backend
            for (Integer blockId : blockIds) {
                final int blockIdFin = blockId;
                completionService.submit(new Callable<ECBlock>() {
                    @Override
                    public ECBlock call() throws Exception {
                        return readCacheBlock(key, blockIdFin);
                    }
                });
            }
        }

        // wait for an answer for each block (success / error)
        int success = 0;
        int errors = 0;
        while (success + errors < targetBlocksNum) {
            try {
                Future<ECBlock> resultFuture = completionService.take();
                ECBlock ecblock = resultFuture.get();
                if (ecblock != null) {
                    ecblocks.add(ecblock);
                    success++;
                } else
                    errors++;
            } catch (Exception e) {
                errors++;
                logger.warn("Error reading block for " + key);
            }
        }
        return ecblocks;
    }

    /**
     * Read data item identified by key
     * @param key identifier for a data item
     * @param keyNum number based on which this key was generated
     * @return actual bytes of the data item
     */
    @Override
    public byte[] read(final String key, final int keyNum) {
        // request info from proxy
        final ProxyReply reply = proxyConnection.requestRecipe(key);
        logger.debug(reply.prettyPrint());
        List<String> cacheRecipe = reply.getCacheRecipe();
        List<String> backendRecipe = reply.getBackendRecipe();

        // read blocks in parallel according to cache and backend recipes from proxy
        List<ECBlock> ecblocks = new ArrayList<ECBlock>();
        CompletionService<List<ECBlock>> completionService = new ExecutorCompletionService<List<ECBlock>>(executor);

        // read blocks from cache (readCache will fall back to backend, if need be)
        if (cacheRecipe.size() > 0) {
            completionService.submit(new Callable<List<ECBlock>>() {
                @Override
                public List<ECBlock> call() throws Exception {
                    return readCache(key, cacheRecipe);
                }
            });
        } else {
            // if no block is cached for this key not cached, count this read as a cache miss
            cacheMisses.incrementAndGet();
        }

        // read blocks from backend
        if (backendRecipe.size() > 0) {
            completionService.submit(new Callable<List<ECBlock>>() {
                @Override
                public List<ECBlock> call() throws Exception {
                    return readBackend(key, backendRecipe);
                }
            });
        }

        // wait for k blocks (note: the proxy does not try to cache more than k blocks)
        int success = 0;
        int errors = 0;
        while (success < LonghairLib.k) {
            try {
                Future<List<ECBlock>> resultFuture = completionService.take();
                List<ECBlock> ecblocksRes = resultFuture.get();
                if (ecblocksRes != null) {
                    ecblocks.addAll(ecblocksRes);
                    success += ecblocksRes.size();
                } else
                    errors++;
            } catch (Exception e) {
                errors++;
                logger.debug("Exception reading block.");
            }
            if (errors > LonghairLib.m)
                break;
        }

        // extract bytes from blocks + count how many blocks were read from cache and how many from backend
        Set<byte[]> blockBytes = new HashSet<>();
        int fromCache = 0;
        int fromBackend = 0;
        for (ECBlock ecblock : ecblocks) {
            blockBytes.add(ecblock.getBytes());
            if (ecblock.getStorage() == Storage.CACHE)
                fromCache++;
            else if (ecblock.getStorage() == Storage.BACKEND)
                fromBackend++;
        }

        // stats: this was a hit / miss / partial hit
        if (fromCache > 0) {
            if (fromBackend == 0) {
                // all blocks read from cache
                cacheHits.incrementAndGet();
            } else {
                // subset of blocks read from cache
                cachePartialHits.incrementAndGet();
            }
        } else {
            if (fromBackend > 0) {
                // all blocks read from backend
                cacheMisses.incrementAndGet();
            }
        }

        // decode data + return it
        byte[] data = LonghairLib.decode(blockBytes);
        logger.info("Read " + key + " " + data.length + " bytes Cache: " + fromCache + " Backend: " + fromBackend);
        return data;
    }

    @Override
    public Status update(String key, byte[] value) {
        return null;
    }

    @Override
    public Status insert(String key, byte[] value) {
        return null;
    }

    @Override
    public Status delete(String key) {
        return null;
    }
}
