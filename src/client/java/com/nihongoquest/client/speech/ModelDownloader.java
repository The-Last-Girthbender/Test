package com.nihongoquest.client.speech;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and unzips the Vosk Japanese model into config/nihongoquest/. */
final class ModelDownloader {
    private ModelDownloader() {}

    static void downloadAndUnzip(String url, Path targetDir, Consumer<String> progress) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);
        Path part = targetDir.resolve("speech-model-download.zip.part");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "NihongoQuest-Minecraft-Mod")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("download failed (HTTP " + response.statusCode() + ")");
        }

        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPercent) {
                        lastPercent = pct;
                        progress.accept(pct + "%");
                    }
                } else {
                    progress.accept((done / (1024 * 1024)) + " MB");
                }
            }
        }

        progress.accept("unzipping");
        Path root = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(part))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path dest = root.resolve(entry.getName()).normalize();
                if (!dest.startsWith(root)) throw new IOException("unsafe path in zip: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
