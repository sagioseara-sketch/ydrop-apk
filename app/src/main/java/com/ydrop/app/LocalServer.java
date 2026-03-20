package com.ydrop.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class LocalServer extends NanoHTTPD {

    private static final String TAG = "YDROP";

    private final Context ctx;
    private final File downloadDir;
    private final String ytdlpPath;
    private final String ffmpegPath;

    public LocalServer(Context ctx, int port) throws IOException {
        super(port);
        this.ctx = ctx;

        // Both binaries are bundled in the APK as .so files
        // Android extracts them to nativeLibraryDir which is ALWAYS executable
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        this.ytdlpPath  = nativeDir + "/libytdlp.so";
        this.ffmpegPath = nativeDir + "/libffmpeg_ydrop.so";

        this.downloadDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YTDownloader");
        if (!downloadDir.exists()) downloadDir.mkdirs();

        Log.d(TAG, "yt-dlp  : " + ytdlpPath  + " exists=" + new File(ytdlpPath).exists());
        Log.d(TAG, "ffmpeg  : " + ffmpegPath  + " exists=" + new File(ffmpegPath).exists());
        Log.d(TAG, "savedir : " + downloadDir.getAbsolutePath());
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> p = session.getParms();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) {
                InputStream is = ctx.getAssets().open("index.html");
                byte[] b = readBytes(is);
                return newFixedLengthResponse(Response.Status.OK,
                    "text/html; charset=utf-8", new ByteArrayInputStream(b), b.length);
            }
            if (uri.equals("/health")) {
                boolean ready = new File(ytdlpPath).exists()
                             && new File(ffmpegPath).exists();
                JSONObject obj = new JSONObject();
                obj.put("ready", ready);
                Response r = newFixedLengthResponse(Response.Status.OK,
                    "application/json", obj.toString());
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            }
            if (uri.equals("/info"))
                return info(p.getOrDefault("url", ""));
            if (uri.equals("/download"))
                return download(p.getOrDefault("url", ""),
                                p.getOrDefault("mode", "video"),
                                p.getOrDefault("quality", "best"));
        } catch (Exception e) {
            Log.e(TAG, "serve: " + e.getMessage());
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                Response r = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", err.toString());
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            } catch (Exception ignored) {}
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    // ── /info ─────────────────────────────────────────────────────────────────
    private Response info(String url) throws Exception {
        if (url.isEmpty()) {
            return jsonError("No URL provided", Response.Status.BAD_REQUEST);
        }

        ProcessBuilder pb = new ProcessBuilder(
            ytdlpPath,
            "--dump-json",
            "--no-playlist",
            "--socket-timeout", "30",
            "--ffmpeg-location", ffmpegPath,
            url);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(readBytes(proc.getInputStream())).trim();
        int code = proc.waitFor();

        if (code != 0 || out.isEmpty() || !out.startsWith("{")) {
            return jsonError(
                "Could not fetch video. Check it is a valid YouTube link.",
                Response.Status.INTERNAL_ERROR);
        }

        JSONObject info  = new JSONObject(out);
        JSONArray  fmts  = buildFormats(info.optJSONArray("formats"));

        JSONObject resp = new JSONObject();
        resp.put("title",     info.optString("title",           "Unknown"));
        resp.put("thumbnail", info.optString("thumbnail",       ""));
        resp.put("duration",  info.optString("duration_string", "?"));
        resp.put("uploader",  info.optString("uploader",        ""));
        resp.put("views",     info.optInt   ("view_count",      0));
        resp.put("formats",   fmts);

        Response r = newFixedLengthResponse(Response.Status.OK,
            "application/json", resp.toString());
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private JSONArray buildFormats(JSONArray raw) throws Exception {
        JSONArray out   = new JSONArray();
        int[]    heights = {2160, 1440, 1080, 720, 480, 360};
        boolean[] seen   = new boolean[heights.length];
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject f  = raw.getJSONObject(i);
            int    h  = f.optInt   ("height", 0);
            String vc = f.optString("vcodec", "none");
            for (int j = 0; j < heights.length; j++) {
                if (h == heights[j] && !vc.equals("none") && !seen[j]) {
                    JSONObject fmt = new JSONObject();
                    fmt.put("label", h + "p");
                    fmt.put("value", String.valueOf(h));
                    out.put(fmt);
                    seen[j] = true;
                }
            }
        }
        return out;
    }

    // ── /download (SSE) ───────────────────────────────────────────────────────
    private Response download(String url, String mode, String quality) throws Exception {
        PipedOutputStream pout = new PipedOutputStream();
        PipedInputStream  pin  = new PipedInputStream(pout);

        new Thread(() -> {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(pout))) {

                // Build format string
                String fmt;
                if (mode.equals("audio")) {
                    fmt = "bestaudio/best";
                } else if (quality.equals("best")) {
                    fmt = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best";
                } else {
                    fmt = "bestvideo[height<=" + quality + "][ext=mp4]"
                        + "+bestaudio[ext=m4a]"
                        + "/bestvideo[height<=" + quality + "]+bestaudio"
                        + "/best[height<=" + quality + "]";
                }

                String outPath = new File(downloadDir, "%(title)s.%(ext)s")
                    .getAbsolutePath();

                ProcessBuilder pb;
                if (mode.equals("audio")) {
                    pb = new ProcessBuilder(
                        ytdlpPath,
                        "--no-playlist",
                        "-f", fmt,
                        "-x", "--audio-format", "mp3", "--audio-quality", "0",
                        "--ffmpeg-location", ffmpegPath,
                        "--newline", "--progress",
                        "-o", outPath,
                        url);
                } else {
                    pb = new ProcessBuilder(
                        ytdlpPath,
                        "--no-playlist",
                        "-f", fmt,
                        "--merge-output-format", "mp4",
                        "--ffmpeg-location", ffmpegPath,
                        "--newline", "--progress",
                        "-o", outPath,
                        url);
                }
                pb.redirectErrorStream(true);

                sse(pw, "start", "Starting download...");
                Process proc = pb.start();

                Pattern pat = Pattern.compile(
                    "\\[download\\]\\s+([\\d.]+)%"
                    + "\\s+of\\s+(\\S+)"
                    + "\\s+at\\s+(\\S+)"
                    + "\\s+ETA\\s+(\\S+)");

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = pat.matcher(line);
                        if (m.find()) {
                            JSONObject o = new JSONObject();
                            o.put("type",    "progress");
                            o.put("percent", Double.parseDouble(m.group(1)));
                            o.put("size",    m.group(2));
                            o.put("speed",   m.group(3));
                            o.put("eta",     m.group(4));
                            pw.print("data: " + o + "\n\n");
                            pw.flush();
                        } else if (line.contains("Merging") || line.contains("Merger")) {
                            sse(pw, "status", "Merging video + audio...");
                        } else if (line.toLowerCase().contains("[ffmpeg]")) {
                            sse(pw, "status", "Processing...");
                        }
                    }
                }

                if (proc.waitFor() == 0) {
                    JSONObject done = new JSONObject();
                    done.put("type",   "done");
                    done.put("folder", downloadDir.getAbsolutePath());
                    pw.print("data: " + done + "\n\n");
                } else {
                    sse(pw, "fail", "Download failed. Please try again.");
                }
                pw.flush();

            } catch (Exception e) {
                Log.e(TAG, "download thread: " + e.getMessage());
            }
        }).start();

        Response r = newChunkedResponse(Response.Status.OK, "text/event-stream", pin);
        r.addHeader("Cache-Control",               "no-cache");
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Response jsonError(String msg, Response.Status status) throws Exception {
        JSONObject e = new JSONObject();
        e.put("error", msg);
        Response r = newFixedLengthResponse(status, "application/json", e.toString());
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private void sse(PrintWriter pw, String type, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type",    type);
            o.put("message", msg);
            pw.print("data: " + o + "\n\n");
            pw.flush();
        } catch (Exception ignored) {}
    }

    private byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }
}
