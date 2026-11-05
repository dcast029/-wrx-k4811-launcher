package com.k4811.puente;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Servidor HTTP mínimo (solo GET/POST simples, una respuesta por conexión). */
public final class MiniHttp {

    public static final class Request {
        public String method;
        public String path;
        public final Map<String, String> q = new HashMap<>();
        public String remote;
        public byte[] body = new byte[0];

        public String p(String k, String def) {
            String v = q.get(k);
            return v == null ? def : v;
        }
    }

    public static final class Response {
        public int status = 200;
        public String type = "text/plain; charset=utf-8";
        public byte[] bytes;
        /** Si no es null, se transmite este archivo (para APK y descargas grandes). */
        public File file;
        public String fileName;

        public static Response text(String s) {
            Response r = new Response();
            try {
                r.bytes = s.getBytes("UTF-8");
            } catch (Exception e) {
                r.bytes = new byte[0];
            }
            return r;
        }

        public static Response json(String s) {
            Response r = text(s);
            r.type = "application/json; charset=utf-8";
            return r;
        }

        public static Response error(int code, String msg) {
            Response r = text(msg + "\n");
            r.status = code;
            return r;
        }
    }

    public interface Handler {
        Response handle(Request r) throws Exception;
    }

    private static final String TAG = "PuenteHttp";
    private final int port;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private volatile boolean running;

    public MiniHttp(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() throws Exception {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (Exception e) {
                    if (running) Log.w(TAG, "accept: " + e);
                }
            }
        }, "puente-http");
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                s.close();
                return;
            }
            Request req = new Request();
            req.remote = s.getInetAddress().getHostAddress();
            String[] parts = line.split(" ");
            req.method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    len = Integer.parseInt(h.substring(c + 1).trim());
                }
            }
            if (len > 0 && len < 20 * 1024 * 1024) {
                byte[] b = new byte[len];
                int n = 0;
                while (n < len) {
                    int r = in.read(b, n, len - n);
                    if (r < 0) break;
                    n += r;
                }
                req.body = b;
            }
            int qi = target.indexOf('?');
            req.path = URLDecoder.decode(qi < 0 ? target : target.substring(0, qi), "UTF-8");
            if (qi >= 0) {
                for (String kv : target.substring(qi + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                    String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                    req.q.put(k, v);
                }
            }
            Response res;
            try {
                res = handler.handle(req);
            } catch (Throwable e) {
                res = Response.error(500, "Error: " + e);
            }
            write(s.getOutputStream(), res);
        } catch (Exception e) {
            Log.w(TAG, "serve: " + e);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void write(OutputStream out, Response r) throws Exception {
        long length = r.file != null ? r.file.length() : r.bytes.length;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(r.status == 200 ? " OK" : " ERR").append("\r\n");
        h.append("Content-Type: ").append(r.type).append("\r\n");
        h.append("Content-Length: ").append(length).append("\r\n");
        if (r.fileName != null) {
            h.append("Content-Disposition: attachment; filename=\"").append(r.fileName).append("\"\r\n");
        }
        h.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        if (r.file != null) {
            try (InputStream in = new FileInputStream(r.file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } else {
            out.write(r.bytes);
        }
        out.flush();
    }
}
