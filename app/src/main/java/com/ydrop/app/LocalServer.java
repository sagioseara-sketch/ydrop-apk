package com.ydrop.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import org.json.JSONArray;
import org.json.JSONObject;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.IStatus;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalServer extends NanoHTTPD {

    private static final String TAG = "YDROP";
    private static final String YTDLP_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64";

    private final Context ctx;
    private File ytdlpBin;
    private File downloadDir;

    public LocalServer(Context ctx, int port) throws IOException {
        super("localhost", port);
        this.ctx = ctx;
        this.downloadDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YTDownloader"
        );
        if (!downloadDir.exists()) downloadDir.mkdirs();

        // yt-dlp binary path in app private storage
        this.ytdlpBin = new File(ctx.getFilesDir(), "yt-dlp");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();

        try {
            // Serve HTML
            if (uri.equals("/") || uri.equals("/index.html")) {
                InputStream is = ctx.getAssets().open("index.html");
                return Response.newFixedLengthResponse(Status.OK, "text/html", is, is.available());
            }

            // Fetch video info
            if (uri.equals("/info")) {
                String url = params.getOrDefault("url", "");
                return handleInfo(url);
            }

            // Download with SSE
            if (uri.equals("/download")) {
                String url     = params.getOrDefault("url", "");
                String mode    = params.getOrDefault("mode", "video");
                String quality = params.getOrDefault("quality", "best");
                return handleDownload(url, mode, quality);
            }

        } catch (Exception e) {
            Log.e(TAG, "Server error: " + e.getMessage());
        }

        return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found");
    }

    // ── Ensure yt-dlp binary exists, download if not ─────────────────────────
    private synchronized void ensureYtDlp() throws Exception {
        if (ytdlpBin.exists() && ytdlpBin.canExecute()) return;

        Log.d(TAG, "Downloading yt-dlp binary...");
        URL url = new URL(YTDLP_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(ytdlpBin)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        ytdlpBin.setExecutable(true);
        Log.d(TAG, "yt-dlp ready at: " + ytdlpBin.getAbsolutePath());
    }

    // ── /info ─────────────────────────────────────────────────────────────────
    private Response handleInfo(String url) {
        try {
            ensureYtDlp();
            ProcessBuilder pb = new ProcessBuilder(
                ytdlpBin.getAbsolutePath(),
                "--dump-json", "--no-playlist", url
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            proc.waitFor();

            JSONObject info = new JSONObject(sb.toString());

            // Build formats list
            JSONArray formatsRaw = info.optJSONArray("formats");
            JSONArray formats = new JSONArray();
            int[] heights = {2160, 1440, 1080, 720, 480, 360};
            boolean[] seen = new boolean[heights.length];

            if (formatsRaw != null) {
                for (int i = 0; i < formatsRaw.length(); i++) {
                    JSONObject f = formatsRaw.getJSONObject(i);
                    int h = f.optInt("height", 0);
                    String vcodec = f.optString("vcodec", "none");
                    for (int j = 0; j < heights.length; j++) {
                        if (h == heights[j] && !vcodec.equals("none") && !seen[j]) {
                            JSONObject fmt = new JSONObject();
                            fmt.put("label", heights[j] + "p");
                            fmt.put("value", String.valueOf(heights[j]));
                            formats.put(fmt);
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
            resp.put("formats", formats);

            Response r = Response.newFixedLengthResponse(Status.OK, "application/json", resp.toString());
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;

        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", err.toString());
            } catch (Exception ignored) {}
        }
        return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error");
    }

    // ── /download (SSE) ───────────────────────────────────────────────────────
    private Response handleDownload(String url, String mode, String quality) {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream  pipeIn;

        try {
            pipeIn = new PipedInputStream(pipeOut);
        } catch (IOException e) {
            return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Pipe error");
        }

        // Run download in background thread
        final PipedOutputStream finalPipeOut = pipeOut;
        new Thread(() -> {
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(finalPipeOut)))) {
                ensureYtDlp();

                // Build format string
                String fmt;
                if (mode.equals("audio")) {
                    fmt = "bestaudio/best";
                } else if (quality.equals("best")) {
                    fmt = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best";
                } else {
                    fmt = "bestvideo[height<=" + quality + "][ext=mp4]+bestaudio[ext=m4a]/best[height<=" + quality + "]";
                }

                String outTmpl = new File(downloadDir, "%(title)s.%(ext)s").getAbsolutePath();

                ProcessBuilder pb;
                if (mode.equals("audio")) {
                    pb = new ProcessBuilder(
                        ytdlpBin.getAbsolutePath(),
                        "--no-playlist", "-f", fmt,
                        "-x", "--audio-format", "mp3", "--audio-quality", "0",
                        "--newline", "--progress",
                        "--ffmpeg-location", getFfmpegPath(),
                        "-o", outTmpl, url
                    );
                } else {
                    pb = new ProcessBuilder(
                        ytdlpBin.getAbsolutePath(),
                        "--no-playlist", "-f", fmt,
                        "--merge-output-format", "mp4",
                        "--newline", "--progress",
                        "--ffmpeg-location", getFfmpegPath(),
                        "-o", outTmpl, url
                    );
                }

                pb.redirectErrorStream(true);
                Process proc = pb.start();

                sendSSE(pw, "start", "Starting download...");

                Pattern progressPat = Pattern.compile(
                    "\\[download\\]\\s+([\\d.]+)%\\s+of\\s+(\\S+)\\s+at\\s+(\\S+)\\s+ETA\\s+(\\S+)"
                );

                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = progressPat.matcher(line);
                        if (m.find()) {
                            JSONObject p = new JSONObject();
                            p.put("type", "progress");
                            p.put("percent", Double.parseDouble(m.group(1)));
                            p.put("size", m.group(2));
                            p.put("speed", m.group(3));
                            p.put("eta", m.group(4));
                            pw.write("data: " + p + "\n\n");
                            pw.flush();
                        } else if (line.contains("Merging") || line.contains("Merger")) {
                            sendSSE(pw, "status", "Merging video + audio...");
                        } else if (line.toLowerCase().contains("ffmpeg")) {
                            sendSSE(pw, "status", "Processing...");
                        }
                    }
                }

                int code = proc.waitFor();
                if (code == 0) {
                    JSONObject done = new JSONObject();
                    done.put("type", "done");
                    done.put("folder", downloadDir.getAbsolutePath());
                    pw.write("data: " + done + "\n\n");
                } else {
                    sendSSE(pw, "fail", "Download failed. Try again.");
                }
                pw.flush();

            } catch (Exception e) {
                try {
                    PrintWriter pw2 = new PrintWriter(new OutputStreamWriter(finalPipeOut));
                    sendSSE(pw2, "fail", e.getMessage());
                    pw2.flush();
                } catch (Exception ignored) {}
            }
        }).start();

        Response resp = Response.newChunkedResponse(Status.OK, "text/event-stream", pipeIn);
        resp.addHeader("Cache-Control", "no-cache");
        resp.addHeader("Access-Control-Allow-Origin", "*");
        return resp;
    }

    private void sendSSE(PrintWriter pw, String type, String message) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type);
            obj.put("message", message);
            pw.write("data: " + obj + "\n\n");
            pw.flush();
        } catch (Exception ignored) {}
    }

    // ── Get ffmpeg path from ffmpeg-kit ───────────────────────────────────────
    private String getFfmpegPath() {
        // ffmpeg-kit bundles ffmpeg — extract to private files dir
        File ffmpegBin = new File(ctx.getFilesDir(), "ffmpeg");
        if (!ffmpegBin.exists()) {
            try {
                InputStream is = ctx.getAssets().open("ffmpeg");
                FileOutputStream fos = new FileOutputStream(ffmpegBin);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close(); is.close();
                ffmpegBin.setExecutable(true);
            } catch (Exception e) {
                // Fall back to ffmpeg-kit's built-in path
                return "ffmpeg";
            }
        }
        return ffmpegBin.getAbsolutePath();
    }
}
