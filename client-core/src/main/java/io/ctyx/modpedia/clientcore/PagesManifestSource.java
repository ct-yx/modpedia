package io.ctyx.modpedia.clientcore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 从 GitHub Pages 读取 latest.json。 */
public final class PagesManifestSource implements WorkerInstaller.ManifestSource {
    private final String url;
    private final int timeoutMillis;

    public PagesManifestSource(String url, int timeoutMillis) {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("清单 URL 不能为空");
        this.url = url.trim();
        this.timeoutMillis = timeoutMillis;
    }

    @Override public WorkerManifest fetch() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("Worker 清单请求失败 HTTP " + status);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > 1024 * 1024) throw new IOException("Worker 清单超过 1 MiB");
            }
            return WorkerManifest.parse(new String(output.toByteArray(), "UTF-8"));
        }
    }
}
