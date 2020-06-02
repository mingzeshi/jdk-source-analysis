package org.frozen.jdk.source;

import com.sun.corba.se.spi.orbutil.threadpool.ThreadPool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SourceAnalysis {

    public static void main(String[] args) throws Exception {
        /**
         * HashMap
         */
        Map<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("", "");

        /**
         * ConcurrentHashMap
         */
        Map<String, String> concurrentHashMap = new ConcurrentHashMap<String, String>();
        concurrentHashMap.put("", "");

        /**
         * ReentrantLock    true/false
         */
        ReentrantLock reentrantLock = new ReentrantLock(true);
        reentrantLock.lock();
        reentrantLock.tryLock();
        reentrantLock.tryLock(1, TimeUnit.MINUTES);
        reentrantLock.unlock();

        /**
         * ReentrantReadWriteLock   true/false
         */
        ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock(true);

        ReentrantReadWriteLock.ReadLock readLock = reentrantReadWriteLock.readLock();
        readLock.lock();
        readLock.tryLock();
        readLock.tryLock(1, TimeUnit.MINUTES);
        readLock.unlock();

        ReentrantReadWriteLock.WriteLock writeLock = reentrantReadWriteLock.writeLock();
        writeLock.lock();
        writeLock.tryLock();
        writeLock.tryLock(1, TimeUnit.MINUTES);
        writeLock.unlock();


        /**
         * Condition
         */
        Condition condition = reentrantLock.newCondition();
        condition.await();
        condition.signal();
        condition.signalAll();

        /**
         * ThreadPool
         */
        Executors.newFixedThreadPool(10);
        ExecutorService threadPool = Executors.newCachedThreadPool();
        Executors.newScheduledThreadPool(10);
        Executors.newSingleThreadExecutor();

        threadPool.shutdown();
        threadPool.shutdownNow();

        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(10, 10,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());

    }
}
