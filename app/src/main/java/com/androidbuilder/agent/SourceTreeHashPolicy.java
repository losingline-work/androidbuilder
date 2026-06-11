package com.androidbuilder.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SourceTreeHashPolicy {
    private SourceTreeHashPolicy() {
    }

    public static String hash(File root) throws IOException {
        return hash(root, null);
    }

    public static String hash(File root, List<String> relativePaths) throws IOException {
        if (root == null) {
            throw new IOException("Source root is null.");
        }
        File canonicalRoot = root.getCanonicalFile();
        Map<String, File> files = new TreeMap<>();
        if (relativePaths == null) {
            collectFiles(canonicalRoot, canonicalRoot, files);
        } else {
            collectRequestedFiles(canonicalRoot, relativePaths, files);
        }
        MessageDigest aggregate = sha256();
        for (Map.Entry<String, File> entry : files.entrySet()) {
            File file = entry.getValue();
            String contentHash = fileHash(file);
            updateText(aggregate, entry.getKey());
            aggregate.update((byte) 0);
            updateText(aggregate, Long.toString(file.length()));
            aggregate.update((byte) 0);
            updateText(aggregate, contentHash);
            aggregate.update((byte) '\n');
        }
        return hex(aggregate.digest());
    }

    private static void collectRequestedFiles(File root, List<String> relativePaths, Map<String, File> files) throws IOException {
        if (relativePaths == null) {
            return;
        }
        for (String rawPath : relativePaths) {
            String path = rawPath == null ? "" : rawPath.trim();
            File target;
            if (path.isEmpty() || ".".equals(path)) {
                target = root;
            } else {
                target = new File(root, PathValidator.normalizeGeneratedPath(path)).getCanonicalFile();
            }
            if (isWithinRoot(root, target)) {
                collectFiles(root, target, files);
            }
        }
    }

    private static void collectFiles(File root, File file, Map<String, File> files) throws IOException {
        if (file == null || !file.exists() || !isWithinRoot(root, file) || isExcluded(root, file)) {
            return;
        }
        if (file.isFile()) {
            files.put(relativePath(root, file), file.getCanonicalFile());
            return;
        }
        if (!file.isDirectory()) {
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(root, child.getCanonicalFile(), files);
        }
    }

    private static boolean isExcluded(File root, File file) throws IOException {
        String relative = relativePath(root, file);
        if (relative.isEmpty()) {
            return false;
        }
        String[] segments = relative.split("/");
        for (String segment : segments) {
            if (".gradle".equals(segment) || "build".equals(segment) || ".DS_Store".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithinRoot(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) {
            return "";
        }
        String relative = filePath.substring(rootPath.length() + 1);
        return relative.replace(File.separatorChar, '/');
    }

    private static String fileHash(File file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available.", error);
        }
    }

    private static void updateText(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(chars);
    }
}
