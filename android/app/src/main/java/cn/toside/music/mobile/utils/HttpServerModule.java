package cn.toside.music.mobile.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量级 HTTP 服务器模块 + 二维码生成
 *
 * 用途：让 TV 端显示一个二维码，手机扫码后在手机上输入脚本网址，
 * 提交后通过事件把 URL 推送到 TV 端的网络导入输入框。
 *
 * - start(port) 启动 HTTP 服务器，返回实际端口
 * - stop() 停止服务器
 * - getQrCodeBase64(text) 生成二维码图片（base64 PNG data URI）
 * - 事件 "httpServerUrl" - 手机提交 URL 时触发，params.url 为提交的 URL
 */
public class HttpServerModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean isRunning = false;
    private int actualPort = 0;

    private static final String HTML_PAGE =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
        "<title>导入网络音乐源</title>" +
        "<style>" +
        "*{box-sizing:border-box;}" +
        "body{font-family:-apple-system,BlinkMacSystemFont,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:20px;margin:0;min-height:100vh;}" +
        ".container{max-width:520px;margin:40px auto;background:#fff;padding:32px 28px;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,0.15);}" +
        ".header{text-align:center;margin-bottom:24px;}" +
        ".header .icon{width:56px;height:56px;background:linear-gradient(135deg,#ff6b6b,#ee5a24);border-radius:50%;margin:0 auto 12px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:28px;font-weight:bold;}" +
        "h2{margin:0 0 6px 0;color:#2c3e50;font-size:22px;font-weight:600;}" +
        ".subtitle{color:#7f8c8d;font-size:13px;margin:0;}" +
        ".form-group{margin-bottom:16px;}" +
        "label{display:block;font-size:14px;color:#34495e;margin-bottom:8px;font-weight:500;}" +
        "input[type=url]{width:100%;padding:14px 16px;border:2px solid #ecf0f1;border-radius:10px;font-size:16px;transition:border-color .2s;background:#fafbfc;}" +
        "input[type=url]:focus{outline:none;border-color:#ff6b6b;background:#fff;}" +
        "button{width:100%;padding:15px;background:linear-gradient(135deg,#ff6b6b,#ee5a24);color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;transition:transform .1s;}" +
        "button:active{transform:scale(.98);}" +
        ".tip{font-size:12px;color:#95a5a6;margin-top:18px;text-align:center;line-height:1.6;padding-top:16px;border-top:1px solid #ecf0f1;}" +
        ".success-wrap{text-align:center;padding:20px 0;}" +
        ".success-icon{width:72px;height:72px;background:#27ae60;border-radius:50%;margin:0 auto 20px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:36px;}" +
        ".success-wrap h2{color:#27ae60;margin-bottom:8px;}" +
        ".success-wrap p{color:#7f8c8d;font-size:14px;margin:0;}" +
        "</style></head><body>" +
        "<div class=\"container\">" +
        "<div class=\"header\">" +
        "<div class=\"icon\">M</div>" +
        "<h2>导入网络音乐源</h2>" +
        "<p class=\"subtitle\">在下方输入脚本订阅地址</p>" +
        "</div>" +
        "<form method=\"POST\" action=\"/submit\">" +
        "<div class=\"form-group\">" +
        "<label>脚本地址</label>" +
        "<input type=\"url\" name=\"url\" placeholder=\"https://example.com/source.js\" required autocomplete=\"off\">" +
        "</div>" +
        "<button type=\"submit\">推送到电视</button>" +
        "</form>" +
        "<div class=\"tip\">输入脚本订阅地址（https:// 开头），<br>点击「推送到电视」后将自动完成导入。</div>" +
        "</div>" +
        "</body></html>";

    private static final String SUCCESS_PAGE =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
        "<title>已推送</title>" +
        "<style>" +
        "body{font-family:-apple-system,BlinkMacSystemFont,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:20px;margin:0;min-height:100vh;}" +
        ".container{max-width:520px;margin:40px auto;background:#fff;padding:48px 28px;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,0.15);text-align:center;}" +
        ".success-icon{width:80px;height:80px;background:#27ae60;border-radius:50%;margin:0 auto 24px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:40px;font-weight:bold;}" +
        "h2{margin:0 0 10px 0;color:#2c3e50;font-size:22px;}" +
        "p{color:#7f8c8d;font-size:14px;line-height:1.6;margin:0;}" +
        "</style></head><body>" +
        "<div class=\"container\">" +
        "<div class=\"success-icon\">&#10003;</div>" +
        "<h2>已推送到电视</h2>" +
        "<p>请在电视上查看导入结果</p>" +
        "</div>" +
        "</body></html>";

    public HttpServerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "HttpServerModule";
    }

    @ReactMethod
    public void start(final int port, final Promise promise) {
        if (isRunning) {
            promise.resolve(actualPort);
            return;
        }
        try {
            serverSocket = new ServerSocket(port);
            actualPort = serverSocket.getLocalPort();
            isRunning = true;
            executor = Executors.newCachedThreadPool();

            // 后台线程接受连接
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning) {
                        try {
                            Socket client = serverSocket.accept();
                            if (executor != null && !executor.isShutdown()) {
                                executor.execute(new ClientHandler(client));
                            } else {
                                client.close();
                            }
                        } catch (IOException e) {
                            // accept 抛异常通常是服务器关闭，正常退出循环
                            break;
                        }
                    }
                }
            }, "HttpServer-Accept").start();

            promise.resolve(actualPort);
        } catch (IOException e) {
            isRunning = false;
            promise.reject("HTTP_SERVER_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        actualPort = 0;
    }

    @ReactMethod
    public void isRunning(Promise promise) {
        promise.resolve(isRunning);
    }

    @ReactMethod
    public void getQrCodeBase64(final String text, final Promise promise) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512, hints);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            bitmap.recycle();

            promise.resolve("data:image/png;base64," + base64);
        } catch (WriterException e) {
            promise.reject("QR_ERROR", e.getMessage());
        } catch (Throwable t) {
            promise.reject("QR_ERROR", t.getMessage() != null ? t.getMessage() : "unknown error");
        }
    }

    private void sendUrlEvent(String url) {
        try {
            WritableMap params = Arguments.createMap();
            params.putString("url", url);
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("httpServerUrl", params);
        } catch (Throwable t) {
            // ignore
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket client;

        ClientHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                client.setSoTimeout(10000);
                InputStream is = client.getInputStream();
                OutputStream os = client.getOutputStream();

                String requestLine = readLine(is);
                if (requestLine == null || requestLine.isEmpty()) {
                    client.close();
                    return;
                }
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 3) {
                    client.close();
                    return;
                }
                String method = requestParts[0];
                String path = requestParts[1];

                int contentLength = 0;
                String headerLine;
                while ((headerLine = readLine(is)) != null && !headerLine.isEmpty()) {
                    String lower = headerLine.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(15).trim());
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }

                String body = "";
                if (contentLength > 0) {
                    byte[] bodyBytes = new byte[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int r = is.read(bodyBytes, read, contentLength - read);
                        if (r < 0) break;
                        read += r;
                    }
                    body = new String(bodyBytes, 0, read, "UTF-8");
                }

                if ("GET".equals(method) && "/".equals(path)) {
                    sendResponse(os, 200, "text/html; charset=utf-8", HTML_PAGE.getBytes("UTF-8"));
                } else if ("POST".equals(method) && "/submit".equals(path)) {
                    String url = parseUrlFromBody(body);
                    if (url != null && !url.isEmpty()) {
                        sendUrlEvent(url);
                    }
                    sendResponse(os, 200, "text/html; charset=utf-8", SUCCESS_PAGE.getBytes("UTF-8"));
                } else if ("OPTIONS".equals(method)) {
                    String corsHeader = "HTTP/1.1 204 No Content\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: *\r\n" +
                            "Content-Length: 0\r\n\r\n";
                    os.write(corsHeader.getBytes("UTF-8"));
                } else {
                    sendResponse(os, 404, "text/plain; charset=utf-8", "Not Found".getBytes("UTF-8"));
                }

                os.flush();
            } catch (IOException e) {
                // ignore
            } finally {
                try {
                    client.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = is.read()) != -1) {
            if (ch == '\r') {
                int next = is.read();
                if (next == '\n') break;
                sb.append((char) ch);
                if (next != -1) sb.append((char) next);
            } else if (ch == '\n') {
                break;
            } else {
                sb.append((char) ch);
            }
        }
        return sb.length() == 0 && ch == -1 ? null : sb.toString();
    }

    private void sendResponse(OutputStream os, int status, String contentType, byte[] body) throws IOException {
        String statusText = status == 200 ? "OK" : (status == 404 ? "Not Found" : "Error");
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "\r\n";
        os.write(header.getBytes("UTF-8"));
        os.write(body);
    }

    private String parseUrlFromBody(String body) {
        if (body == null || body.isEmpty()) return null;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "url".equals(kv[0])) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    @Override
    public void onCatalystInstanceDestroy() {
        stop();
        super.onCatalystInstanceDestroy();
    }
}
