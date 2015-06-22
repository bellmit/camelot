package ru.yandex.qatools.camelot.core.builders;

import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.yandex.qatools.camelot.api.AppConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static ru.yandex.qatools.camelot.util.DateUtil.isTimePassedSince;

/**
 * @author Ilya Sadykov (mailto: smecsia@yandex-team.ru)
 * @author Innokenty Shuvalov (mailto: innokenty@yandex-team.ru)
 */
public class QuartzInitializerImpl implements QuartzInitializer {

    public static final String HEARTBEAT_TIMEOUT = "camelot.quartz.master.heartBeatTimeout";
    public static final String HEARTBEAT_INTERVAL = "camelot.quartz.master.heartBeatInterval";
    private volatile long lastHeartBeatTime = 0;

    private final ExecutorService singleThread = newSingleThreadExecutor();
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Scheduler scheduler;
    protected final long heartBeatTimeout;
    protected final long heartBeatInterval;

    protected Lock lock;

    public QuartzInitializerImpl(Scheduler scheduler, AppConfig config) {
        this.scheduler = scheduler;
        this.heartBeatTimeout = config.getLong(HEARTBEAT_TIMEOUT);
        this.heartBeatInterval = config.getLong(HEARTBEAT_INTERVAL);
    }

    protected synchronized Lock getLock() {
        if (lock == null) {
            lock = new ReentrantLock();
        }
        return lock;
    }

    /**
     * Returns the Quartz lock within HazelCast
     */
    @Override
    public boolean lock() throws InterruptedException {
        return getLock().tryLock(heartBeatInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts the Quartz scheduler
     */
    @Override
    public synchronized void start() {
        singleThread.submit(new Runnable() {
            @Override
            public void run() {
                waitForTheMasterLock();
                startMasterLoop();
            }
        });
    }

    /**
     * Restarts the scheduler
     */
    @Override
    public synchronized void restart() {
        stop();
        start();
    }

    @Override
    public synchronized void standby() {
        try {
            scheduler.standby();
        } catch (Exception e) {
            logger.warn("Exception during the standby of the Quartz!", e);
        }
    }

    /**
     * Unlocks the Quartz scheduler
     */
    @Override
    public synchronized void stop() {
        try {
            scheduler.shutdown();
            unlock();
        } catch (IllegalStateException e) {
            logger.warn("Exception while trying to stop quartz lock: Locking service is already inactive!", e);
        } catch (Exception e) {
            logger.warn("Exception during the stop of the master Quartz!", e);
        }
    }

    @Override
    public void unlock() {
        getLock().unlock();
    }

    @Override
    public void updateHeartBeat() {
        lastHeartBeatTime = currentTimeMillis();
    }

    @Override
    public boolean isMaster() {
        return (lock instanceof ReentrantLock && ((ReentrantLock) lock).isHeldByCurrentThread());
    }

    @Override
    public long getLastHartbeat() {
        return lastHeartBeatTime;
    }


    private void waitForTheMasterLock() {
        while (true) {
            if (lockAndStartScheduler()) {
                break;
            }
            logger.debug("Checking if master Quartz if dead...");
            if (isTimePassedSince(heartBeatTimeout, getLastHartbeat())) {
                logger.warn("Last master Quartz heartbeat timeout reached! Unlocking the Quartz lock!");
                unlock();
            }
        }
    }

    private void startMasterLoop() {
        singleThread.submit(new Runnable() {
            @Override
            public void run() {
                logger.info("Starting master Quartz heartbeat loop!");
                while (true) {
                    logger.debug("Updating master Quartz heartbeat loop...");
                    updateHeartBeat();
                    try {
                        sleep(heartBeatInterval);
                        if (!isMaster()) {
                            throw new RuntimeException("Lock is not held by me anymore, need to restart scheduler!");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to update heartbeat interval! Restarting scheduler...", e);
                        restart();
                        break;
                    }
                }
            }
        });
    }

    private boolean lockAndStartScheduler() {
        try {
            if (lock()) {
                scheduler.start();
                logger.warn("This node is now the master Quartz!");
                return true;
            }
        } catch (Exception e) {
            stop();
            logger.warn("Unable to start scheduler", e);
        }
        return false;
    }

}
