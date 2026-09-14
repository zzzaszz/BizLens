package zzz.service.forum.utils;

public class SnowflakeIdGenerator {

    private static final long START_TIMESTAMP = 1700000000000L;

    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_BITS = 5L;
    private static final long DATACENTER_BITS = 5L;

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_ID_MAX = (1L << WORKER_BITS) - 1;
    private static final long DATACENTER_ID_MAX = (1L << DATACENTER_BITS) - 1;

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > WORKER_ID_MAX || workerId < 0) {
            throw new IllegalArgumentException("workerId out of range");
        }
        if (datacenterId > DATACENTER_ID_MAX || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId out of range");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("clock moved backwards");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis();
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - START_TIMESTAMP) << (WORKER_BITS + DATACENTER_BITS + SEQUENCE_BITS))
                | (datacenterId << (SEQUENCE_BITS + WORKER_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitNextMillis() {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
