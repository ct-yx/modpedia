package io.ctyx.modpedia.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 静态手册扫描结果。 */
public final class ManualScanResult {
    private final List<ManualSourceDescriptor> sources;
    private final List<String> warnings;
    private final int archiveCount;
    private final Date scannedAt;

    public ManualScanResult(
            List<ManualSourceDescriptor> sources,
            List<String> warnings,
            int archiveCount,
            Date scannedAt
    ) {
        this.sources = Collections.unmodifiableList(new ArrayList<ManualSourceDescriptor>(sources));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.archiveCount = Math.max(0, archiveCount);
        this.scannedAt = scannedAt == null ? new Date() : new Date(scannedAt.getTime());
    }

    public List<ManualSourceDescriptor> getSources() {
        return sources;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public int getArchiveCount() {
        return archiveCount;
    }

    public Date getScannedAt() {
        return new Date(scannedAt.getTime());
    }
}
