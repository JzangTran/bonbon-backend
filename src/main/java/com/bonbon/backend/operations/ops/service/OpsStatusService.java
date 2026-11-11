package com.bonbon.backend.operations.ops.service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import com.bonbon.backend.operations.ops.dto.OpsStatusView;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Pings the parts the platform stands on and reports the running instance's numbers. Nothing here changes anything. */
@Service
public class OpsStatusService {

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final Environment environment;

    OpsStatusService(JdbcClient jdbc, StringRedisTemplate redis, Environment environment) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.environment = environment;
    }

    public OpsStatusView status() {
        List<OpsStatusView.Check> checks = new ArrayList<>();
        checks.add(check("Cơ sở dữ liệu (PostgreSQL)", () -> {
            Integer one = jdbc.sql("select 1").query(Integer.class).single();
            return one == 1 ? "Trả lời" : "Trả lời bất thường";
        }));
        checks.add(check("Redis", () -> {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            return pong == null ? "Không có phản hồi" : "Trả lời";
        }));
        Runtime runtime = Runtime.getRuntime();
        long mb = 1024 * 1024;
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        boolean allOk = checks.stream().allMatch(OpsStatusView.Check::ok);
        return new OpsStatusView(allOk ? "UP" : "DEGRADED", Instant.now(), started,
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                Arrays.asList(environment.getActiveProfiles()), System.getProperty("java.version"), runtime.availableProcessors(),
                (runtime.totalMemory() - runtime.freeMemory()) / mb, runtime.maxMemory() / mb, checks);
    }

    private static OpsStatusView.Check check(String name, Callable<String> probe) {
        long begin = System.nanoTime();
        try {
            String detail = probe.call();
            return new OpsStatusView.Check(name, true, (System.nanoTime() - begin) / 1_000_000, detail);
        } catch (Exception e) {
            return new OpsStatusView.Check(name, false, (System.nanoTime() - begin) / 1_000_000, "Lỗi: " + e.getClass().getSimpleName());
        }
    }
}
