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

            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long heapMaxMb = rt.maxMemory() / (1024 * 1024);
            long uptimeHours =
                    ManagementFactory.getRuntimeMXBean().getUptime() / 3_600_000L;

            String line = "RESOURCE GAUGE uptime={}h threads={} peak={} started={} "
                    + "processes={} soffice={} heap={}/{}MB";
            if (live >= THREAD_WARN_THRESHOLD) {
                log.warn(line + " — thread count is high; suspect a leak",
                        uptimeHours, live, peak, started, processes, soffice,
                        heapUsedMb, heapMaxMb);
            } else {
                log.info(line, uptimeHours, live, peak, started, processes,
                        soffice, heapUsedMb, heapMaxMb);
            }
        } catch (Exception e) {
            log.warn("Resource gauge failed: {}", e.getMessage());
        }
    }
}
