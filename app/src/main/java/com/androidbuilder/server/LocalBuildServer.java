package com.androidbuilder.server;

import android.content.Context;

import com.androidbuilder.R;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.util.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class LocalBuildServer {
    public interface Listener {
        void onJobChanged(long projectId, long jobId);
    }

    private final AppRepository repository;
    private final Listener listener;
    private final Context context;
    private final String token;
    private ServerSocket serverSocket;
    private Thread thread;

    public LocalBuildServer(AppRepository repository, Listener listener) {
        this(null, repository, listener);
    }

    public LocalBuildServer(Context context, AppRepository repository, Listener listener) {
        this.context = context == null ? null : context.getApplicationContext();
        this.repository = repository;
        this.listener = listener;
        this.token = createToken();
    }

    public synchronized void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        serverSocket = new ServerSocket(0);
        thread = new Thread(this::acceptLoop, "local-build-server");
        thread.start();
    }

    public synchronized void stop() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public String callbackUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    public String token() {
        return token;
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handle(socket), "local-build-request").start();
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket) {
            BufferedInputStream rawIn = new BufferedInputStream(closeable.getInputStream());
            String requestLine = readLine(rawIn);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeText(closeable, 400, "Bad request");
                return;
            }
            Map<String, String> headers = readHeaders(rawIn);
            if (!token.equals(headers.getOrDefault("x-build-token", ""))) {
                writeText(closeable, 401, "Unauthorized");
                return;
            }
            String method = parts[0];
            String path = parts[1].split("\\?")[0];
            String[] pathParts = path.split("/");
            if (pathParts.length < 6 || !"projects".equals(pathParts[1]) || !"jobs".equals(pathParts[3])) {
                writeText(closeable, 404, "Not found");
                return;
            }
            long projectId = Long.parseLong(pathParts[2]);
            long jobId = Long.parseLong(pathParts[4]);
            String action = pathParts[5];
            if ("GET".equals(method) && "project.zip".equals(action)) {
                sendProjectZip(closeable, projectId, jobId);
            } else if ("POST".equals(method) && "logs".equals(action)) {
                saveLogs(projectId, jobId, rawIn, contentLength(headers));
                writeText(closeable, 200, "OK");
            } else if ("POST".equals(method) && "artifact".equals(action)) {
                saveArtifact(projectId, jobId, rawIn, contentLength(headers));
                writeText(closeable, 200, "OK");
            } else if ("POST".equals(method) && "result".equals(action)) {
                saveResult(projectId, jobId, rawIn, contentLength(headers));
                writeText(closeable, 200, "OK");
            } else {
                writeText(closeable, 404, "Not found");
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        int previous = -1;
        int next;
        while ((next = in.read()) != -1) {
            if (previous == '\r' && next == '\n') {
                out.setLength(out.length() - 1);
                break;
            }
            out.append((char) next);
            previous = next;
        }
        if (next == -1 && out.length() == 0) {
            return null;
        }
        return out.toString();
    }

    private void sendProjectZip(Socket socket, long projectId, long jobId) throws IOException {
        File zip = new File(repository.jobDir(projectId, jobId), "project.zip");
        if (!zip.exists()) {
            writeText(socket, 404, "Missing project zip");
            return;
        }
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + zip.length() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(zip)) {
            FileUtils.copy(in, out);
        }
        out.flush();
    }

    private void saveLogs(long projectId, long jobId, InputStream in, int length) throws IOException {
        File log = new File(repository.jobDir(projectId, jobId), "build.log");
        copyFixed(in, new FileOutputStream(log, true), length);
        BuildJobRecord job = repository.getBuildJob(jobId);
        repository.updateBuildJob(jobId, "building", "termux", log.getAbsolutePath(), job == null ? null : job.apkPath, null, job == null ? 0 : job.retryCount);
        listener.onJobChanged(projectId, jobId);
    }

    private void saveArtifact(long projectId, long jobId, InputStream in, int length) throws IOException {
        File apk = new File(repository.jobDir(projectId, jobId), "app-debug.apk");
        copyFixed(in, new FileOutputStream(apk), length);
        BuildJobRecord job = repository.getBuildJob(jobId);
        repository.addArtifact(projectId, jobId, "apk", apk.getAbsolutePath());
        repository.updateBuildJob(jobId, "built", "artifact_received", job == null ? null : job.logsPath, apk.getAbsolutePath(), null, job == null ? 0 : job.retryCount);
        listener.onJobChanged(projectId, jobId);
    }

    private void saveResult(long projectId, long jobId, InputStream in, int length) throws IOException {
        byte[] bytes = readFixed(in, length);
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        BuildJobRecord job = repository.getBuildJob(jobId);
        String status = body.startsWith("success") ? "success" : "failed";
        String error = "failed".equals(status) ? body : null;
        repository.updateBuildJob(jobId, status, "finished", job == null ? null : job.logsPath, job == null ? null : job.apkPath, error, job == null ? 0 : job.retryCount);
        repository.addMessage(projectId, "assistant", buildResultMessage(status, body), jobId);
        listener.onJobChanged(projectId, jobId);
    }

    private String buildResultMessage(String status, String body) {
        if ("success".equals(status)) {
            return context == null
                    ? "Build complete: success. APK is ready."
                    : context.getString(R.string.build_summary_success);
        }
        String detail = firstDetailLine(body);
        return context == null
                ? "Build complete: failed. " + detail
                : context.getString(R.string.build_summary_failed, detail);
    }

    private String firstDetailLine(String detail) {
        if (detail != null) {
            String[] lines = detail.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed.length() > 240 ? trimmed.substring(0, 240).trim() + "..." : trimmed;
                }
            }
        }
        return context == null
                ? "See the build log for details."
                : context.getString(R.string.build_summary_detail_missing);
    }

    private int contentLength(Map<String, String> headers) {
        try {
            return Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void copyFixed(InputStream in, OutputStream out, int length) throws IOException {
        try (OutputStream closeable = out) {
            byte[] bytes = readFixed(in, length);
            closeable.write(bytes);
        }
    }

    private byte[] readFixed(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[Math.max(length, 0)];
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return buffer;
    }

    private void writeText(Socket socket, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String createToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
