package com.ydrop.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class LocalServer extends NanoHTTPD {

    private static final String TAG = "YDROP";
    private static final String YTDLP_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64";

    private final Context ctx;
    private final File ytdlp;
    private final File downloadDir;
    private volatile boolean ytdlpReady = false;

    public LocalServer(Context ctx, int port) throws IOException {
        super(port);
        this.ctx = ctx;
        this.downloadDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YTDownloader");
        if (!downloadDir.exists()) downloadDir.mkdirs();
        File execDir = ctx.getCodeCacheDir(); if (!execDir.exists()) execDir.mkdirs(); this.ytdlp = new File(execDir, "yt-dlp");

        // Pre-download yt-dlp in background on startup
        new Thread(() -> {
            try { ensureYtDlp(); } catch (Exception e) { Log.e(TAG, "Setup failed: " + e.getMessage()); }
        }).start();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> p = session.getParms();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) {
                InputStream is = ctx.getAssets().open("index.html");
                byte[] bytes = readBytes(is);
                return newFixedLengthResponse(Response.Status.OK,
                    "text/html; charset=utf-8",
                    new ByteArrayInputStream(bytes), bytes.length);
            }
            if (uri.equals("/health")) {
                JSONObject obj = new JSONObject();
                obj.put("ready", ytdlpReady);
                Response r = newFixedLengthResponse(Response.Status.OK,
                    "application/json", obj.toString());
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            }
            if (uri.equals("/info")) {
                return info(p.getOrDefault("url", ""));
            }
            if (uri.equals("/download")) {
                return download(
                    p.getOrDefault("url", ""),
                    p.getOrDefault("mode", "video"),
                    p.getOrDefault("quality", "best"));
            }
        } catch (Exception e) {
            Log.e(TAG, "serve: " + e.getMessage());
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", err.toString());
            } catch (Exception ignored) {}
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    private synchronized void ensureYtDlp() throws Exception {
        if (ytdlp.exists() && ytdlp.canExecute() && ytdlp.length() > 1000) {
            ytdlpReady = true;
            return;
        }
        Log.d(TAG, "Downloading yt-dlp...");
        // Delete any partial file
        if (ytdlp.exists()) ytdlp.delete();

        URL url = new URL(YTDLP_URL);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.connect();

        if (c.getResponseCode() != 200) {
            throw new Exception("HTTP " + c.getResponseCode() + " downloading yt-dlp");
        }

        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(ytdlp)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!ytdlp.setExecutable(true)) {
            throw new Exception("Could not make yt-dlp executable");
        }
        ytdlpReady = true;
        Log.d(TAG, "yt-dlp ready: " + ytdlp.length() + " bytes");
    }

    private Response info(String url) throws Exception {
        if (url.isEmpty()) {
            JSONObject e = new JSONObject(); e.put("error","No URL provided");
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,"application/json",e.toString());
        }
        ensureYtDlp();
        ProcessBuilder pb = new ProcessBuilder(
            ytdlp.getAbsolutePath(),
            "--dump-json",
            "--no-playlist",
            "--socket-timeout", "30",
            url);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(readBytes(proc.getInputStream()));
        int code = proc.waitFor();

        if (code != 0 || out.isEmpty()) {
            JSONObject e = new JSONObject();
            e.put("error", "Could not get video info. Check the URL is a valid YouTube link.");
            Response r = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"application/json",e.toString());
            r.addHeader("Access-Control-Allow-Origin","*");
            return r;
        }

        JSONObject info = new JSONObject(out);
        JSONArray rawFmts = info.optJSONArray("formats");
        JSONArray fmts = new JSONArray();
        int[] heights = {2160, 1440, 1080, 720, 480, 360};
        boolean[] seen = new boolean[heights.length];
        if (rawFmts != null) {
            for (int i = 0; i < rawFmts.length(); i++) {
                JSONObject f = rawFmts.getJSONObject(i);
                int h = f.optInt("height", 0);
                String vc = f.optString("vcodec", "none");
                for (int j = 0; j < heights.length; j++) {
                    if (h == heights[j] && !vc.equals("none") && !seen[j]) {
                        JSONObject fmt = new JSONObject();
                        fmt.put("label", h + "p");
                        fmt.put("value", String.valueOf(h));
                        fmts.put(fmt);
                        seen[j] = true;
                    }
                }
            }
        }
        JSONObject resp = new JSONObject();
        resp.put("title", info.optString("title", "Unknown"));
        resp.put("thumbnail", info.optString("thumbnail", ""));
        resp.put("duration", info.optString("duration_string", "?"));
        resp.put("uploader", info.optString("uploader", ""));
        resp.put("views", info.optInt("view_count", 0));
        resp.put("formats", fmts);
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private Response download(String url, String mode, String quality) throws Exception {
        PipedOutputStream pout = new PipedOutputStream();
        PipedInputStream pin = new PipedInputStream(pout);

        new Thread(() -> {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(pout))) {
                ensureYtDlp();

                String fmt = mode.equals("audio") ? "bestaudio/best"
                    : quality.equals("best")
                        ? "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best"
                        : "bestvideo[height<=" + quality + "][ext=mp4]+bestaudio[ext=m4a]/best[height<=" + quality + "]";

                String outPath = new File(downloadDir, "%(title)s.%(ext)s").getAbsolutePath();

                ProcessBuilder pb;
                if (mode.equals("audio")) {
                    pb = new ProcessBuilder(ytdlp.getAbsolutePath(),
                        "--no-playlist", "-f", fmt,
                        "-x", "--audio-format", "mp3", "--audio-quality", "0",
                        "--newline", "--progress", "-o", outPath, url);
                } else {
                    pb = new ProcessBuilder(ytdlp.getAbsolutePath(),
                        "--no-playlist", "-f", fmt,
                        "--merge-output-format", "mp4",
                        "--newline", "--progress", "-o", outPath, url);
                }
                pb.redirectErrorStream(true);

                sse(pw, "start", "Starting...");
                Process proc = pb.start();
                Pattern pat = Pattern.compile(
                    "\\[download\\]\\s+([\\d.]+)%\\s+of\\s+(\\S+)\\s+at\\s+(\\S+)\\s+ETA\\s+(\\S+)");

                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = pat.matcher(line);
                        if (m.find()) {
                            JSONObject o = new JSONObject();
                            o.put("type","progress");
                            o.put("percent", Double.parseDouble(m.group(1)));
                            o.put("size", m.group(2));
                            o.put("speed", m.group(3));
                            o.put("eta", m.group(4));
                            pw.print("data: " + o + "\n\n"); pw.flush();
                        } else if (line.contains("Merging") || line.contains("Merger")) {
                            sse(pw, "status", "Merging video + audio...");
                        } else if (line.toLowerCase().contains("ffmpeg")) {
                            sse(pw, "status", "Processing...");
                        }
                    }
                }
                if (proc.waitFor() == 0) {
                    JSONObject o = new JSONObject();
                    o.put("type", "done");
                    o.put("folder", downloadDir.getAbsolutePath());
                    pw.print("data: " + o + "\n\n");
                } else {
                    sse(pw, "fail", "Download failed. Please try again.");
                }
                pw.flush();
            } catch (Exception e) {
                Log.e(TAG, "download: " + e.getMessage());
                try {
                    PrintWriter pw2 = new PrintWriter(new OutputStreamWriter(pout));
                    sse(pw2, "fail", e.getMessage()); pw2.flush();
                } catch (Exception ignored) {}
            }
        }).start();

        Response r = newChunkedResponse(Response.Status.OK, "text/event-stream", pin);
        r.addHeader("Cache-Control", "no-cache");
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private void sse(PrintWriter pw, String type, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type); o.put("message", msg);
            pw.print("data: " + o + "\n\n"); pw.flush();
        } catch (Exception ignored) {}
    }

    private byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = is.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }
}
