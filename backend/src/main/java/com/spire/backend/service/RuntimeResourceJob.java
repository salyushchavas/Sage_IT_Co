package com.spire.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Hourly gauge of the JVM's thread and subprocess counts.
 *
 * <p>Why this exists: production died with {@code pthread_create failed
 * (EAGAIN)} / {@code OutOfMemoryError: unable to create native thread} after
 * ~7 days of uptime. That failure mode takes down EVERY request that needs a
 * thread — the PDF preview 500 was a symptom, not a cause — and it gives no
 * warning until it is already fatal. The log also showed
 * {@code pool-235-thread-1}, i.e. 235 accumulated executor pools, and there is
 * no {@code Executors.*} call anywhere in this codebase, so at least one
 * dependency is creating pools per operation and never shutting them down.
 * That leak is still unidentified.
 *
 * <p>Diagnosing it normally means a shell on the container
 * ({@code ps -eLf | wc -l}), which this deployment does not have. So the
 * process reports on itself instead: one line an hour, greppable, showing
 * whether the counts are flat or climbing.
 *
 * <p>Reading the output:
 * <ul>
 *   <li>{@code threads} climbing steadily across hours with {@code soffice=0}
 *       → the executor-pool leak is still live. {@code peak} well above
 *       {@code threads} instead means bursts, not a leak.</li>
 *   <li>{@code soffice} above single digits → LibreOffice processes are
 *       surviving their conversions and the reaper in
 *       {@link AgreementDocumentService} is not catching them.</li>
 *   <li>Both flat over days → whatever leaked is fixed.</li>
 * </ul>
 *
 * <p>Deliberately cheap and total: no allocation of consequence, everything
 * caught. A diagnostic that can break the app it diagnoses is worse than none.
 */
@Component
@Slf4j
public class RuntimeResourceJob {

    /**
     * Threads above this are worth a WARN rather than an INFO. The failure
     * happened somewhere north of a few thousand (the exact ceiling is the
     * container's PID limit, which we cannot read portably), so this sits far
     * enough below to give days of notice, and far enough above a healthy
     * Tomcat + Hikari + scheduler baseline (~50-100) to stay quiet normally.
     */
    private static final int THREAD_WARN_THRESHOLD = 500;

    /**
     * Every hour, offset a minute past startup so it never competes with boot.
     * fixedRate, not cron: what matters is the interval between samples, not
     * the wall-clock time they land on.
     */
    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1H")
    public void logResourceUsage() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            int live = threads.getThreadCount();
            int peak = threads.getPeakThreadCount();
            long started = threads.getTotalStartedThreadCount();

            long processes = -1;
            long soffice = -1;
            try {
                processes = ProcessHandle.allProcesses().count();
                soffice = ProcessHandle.allProcesses()
                        .filter(h -> h.info().command()
                                .map(c -> c.contains("soffice") || c.contains("libreoffice"))
                                .orElse(false))
                        .count();
            } catch (Exception e) {
                // /proc can be restricted; the thread numbers alone still
                // answer the main question.
                log.debug("Process enumeration unavailable: {}", e.getMessage());
            }

            String procBreakdown = procBreakdown();

            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long heapMaxMb = rt.maxMemory() / (1024 * 1024);
            long uptimeHours =
                    ManagementFactory.getRuntimeMXBean().getUptime() / 3_600_000L;

            String line = "RESOURCE GAUGE uptime={}h threads={} peak={} started={} "
                    + "processes={} soffice={} heap={}/{}MB {}";
            if (live >= THREAD_WARN_THRESHOLD) {
                log.warn(line + " — thread count is high; suspect a leak",
                        uptimeHours, live, peak, started, processes, soffice,
                        heapUsedMb, heapMaxMb, procBreakdown);
            } else {
                log.info(line, uptimeHours, live, peak, started, processes,
                        soffice, heapUsedMb, heapMaxMb, procBreakdown);
            }
        } catch (Exception e) {
            log.warn("Resource gauge failed: {}", e.getMessage());
        }
    }

    /**
     * Break the process table down by state and name, straight from /proc.
     *
     * <p>{@link ProcessHandle} is not enough here: a ZOMBIE has already
     * released its command line, so {@code info().command()} is empty and a
     * name-based filter reports zero LibreOffice processes even when the table
     * is full of dead ones. That blind spot is exactly what made an early
     * reading say {@code soffice=0} while the process count climbed 1 → 61 in
     * an hour.
     *
     * <p>Zombies matter because this JVM runs as PID 1. LibreOffice's launcher
     * forks {@code soffice.bin} as a grandchild; when the launcher exits the
     * grandchild reparents to PID 1, and when it exits it stays a zombie
     * because a JVM — unlike a real init — never reaps arbitrary children.
     * Each one holds a PID slot until the container dies, and PIDs and threads
     * draw on the same limit, which is why exhaustion surfaced as
     * {@code pthread_create failed (EAGAIN)}.
     *
     * <p>Linux-only and entirely best-effort: returns {@code procs=?} wherever
     * /proc is absent or unreadable rather than failing the gauge.
     */
    private static String procBreakdown() {
        java.nio.file.Path proc = java.nio.file.Path.of("/proc");
        if (!java.nio.file.Files.isDirectory(proc)) return "procs=?";
        int zombies = 0;
        java.util.Map<String, Integer> byName = new java.util.HashMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> pids =
                     java.nio.file.Files.list(proc)) {
            for (java.nio.file.Path dir : pids.toList()) {
                String pid = dir.getFileName().toString();
                if (!pid.chars().allMatch(Character::isDigit)) continue;
                try {
                    // "pid (comm) STATE ppid ..." — comm can contain spaces and
                    // parens, so split on the LAST ')' rather than tokenizing.
                    String stat = java.nio.file.Files.readString(dir.resolve("stat"));
                    int close = stat.lastIndexOf(')');
                    if (close < 0 || close + 2 >= stat.length()) continue;
                    char state = stat.charAt(close + 2);
                    String name = java.nio.file.Files.readString(dir.resolve("comm")).trim();
                    if (state == 'Z') {
                        zombies++;
                        name = name + "<defunct>";
                    }
                    byName.merge(name, 1, Integer::sum);
                } catch (Exception ignored) {
                    // The process exited mid-read, or /proc entry is
                    // permission-restricted. Either way it is not worth a line.
                }
            }
        } catch (Exception e) {
            return "procs=?";
        }
        String top = byName.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(6)
                .map(en -> en.getKey() + ":" + en.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
        return "zombies=" + zombies + " top=[" + top + "]";
    }
}
