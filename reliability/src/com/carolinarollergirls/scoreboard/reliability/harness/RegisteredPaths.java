package com.carolinarollergirls.scoreboard.reliability.harness;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Which state keys a set of registered paths covers, with the server's rules ({@code JSONStateListener.PathTrie}):
 * a path covers itself and everything below it, and {@code Name(*)} matches any one ID, including IDs containing dots.
 * Re-implemented here so the harness never links to product internals.
 */
public final class RegisteredPaths {
    public RegisteredPaths() {}

    public RegisteredPaths(Collection<String> paths) {
        for (String p : paths) { add(p); }
    }

    public synchronized void add(String path) {
        Node head = root;
        for (String part : split(path)) {
            if (head.isPath) { return; }
            head = head.children.computeIfAbsent(part, k -> new Node());
        }
        head.isPath = true;
    }

    public synchronized boolean covers(String key) { return covers(root, split(key), 0); }

    private static boolean covers(Node node, String[] p, int i) {
        Node head = node;
        for (;; i++) {
            if (head.isPath) { return true; }
            if (i >= p.length) { return false; }
            Node any = head.children.get(p[i].charAt(0) + "*)");
            if (any != null) {
                int j = i;
                while (j < p.length && !p[j].endsWith(")")) { j++; }
                if (covers(any, p, j + 1)) { return true; }
            }
            head = head.children.get(p[i]);
            if (head == null) { return false; }
        }
    }

    private static String[] split(String path) { return path.split("(?=[.(])"); }

    private static final class Node {
        boolean isPath;
        final Map<String, Node> children = new HashMap<>();
    }

    private final Node root = new Node();
}
