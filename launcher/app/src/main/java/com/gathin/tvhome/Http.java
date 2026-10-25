package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** A plain HTTPS GET for the local fork's Home lines (CricketStrip, NewsLine). Runs off the main thread. */
final class Http {
    private Http() {
    }

    static String get(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(10_000);
        http.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        try (InputStream in = http.getInputStream()) {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            http.disconnect();
        }
    }
}
