package local.as300;

import android.util.Xml;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

final class SonyCamera {
    private static final String SERVICE = "urn:schemas-sony-com:service:ScalarWebAPI:1";
    private final CameraWifi wifi;
    private final URL endpoint;
    private final AtomicInteger ids = new AtomicInteger();

    private SonyCamera(CameraWifi wifi, URL endpoint) {
        this.wifi = wifi;
        this.endpoint = endpoint;
    }

    static SonyCamera discover(CameraWifi wifi) throws Exception {
        URL endpoint;
        try {
            endpoint = findEndpoint(wifi);
        } catch (IOException error) {
            endpoint = null;
        }
        if (endpoint == null && wifi.gateway != null) {
            endpoint = new URL("http", wifi.gateway.getHostAddress(), 10000, "/sony/camera");
        }
        if (endpoint == null) throw new IOException("AS300の操作サービスが見つかりません。");
        SonyCamera camera = new SonyCamera(wifi, endpoint);
        camera.call("getAvailableApiList");
        return camera;
    }

    private static URL findEndpoint(CameraWifi wifi) throws Exception {
        byte[] request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + SERVICE + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        android.net.wifi.WifiManager.MulticastLock lock = wifi.wifi.createMulticastLock("AS300 SSDP");
        lock.acquire();
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(wifi.local, 0))) {
            wifi.network.bindSocket(socket);
            socket.setSoTimeout(1000);
            for (int i = 0; i < 3 && !Thread.currentThread().isInterrupted(); i++) {
                socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("239.255.255.250"), 1900));
                DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                if (wifi.gateway != null && !wifi.gateway.equals(packet.getAddress())) continue;
                String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                for (String header : response.split("\r?\n")) {
                    if (!header.toLowerCase(Locale.ROOT).startsWith("location:")) continue;
                    try {
                        String host = packet.getAddress().getHostAddress();
                        URL location = localUrl(header.substring(9).trim(), host);
                        URL endpoint = readEndpoint(wifi, location);
                        if (endpoint != null) return endpoint;
                    } catch (Exception ignored) {
                        // Continue discovery if this description is invalid.
                    }
                }
            }
            return null;
        } finally {
            lock.release();
        }
    }

    private static URL readEndpoint(CameraWifi wifi, URL location) throws Exception {
        HttpURLConnection connection = open(wifi, location);
        try (InputStream input = connection.getInputStream()) {
            XmlPullParser xml = Xml.newPullParser();
            xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            xml.setInput(input, null);
            String service = null;
            String base = null;
            for (int event = xml.getEventType(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                String tag = xml.getName();
                if (event == XmlPullParser.START_TAG && "X_ScalarWebAPI_Service".equals(tag)) {
                    service = null;
                    base = null;
                } else if (event == XmlPullParser.START_TAG && "X_ScalarWebAPI_ServiceType".equals(tag)) {
                    service = xml.nextText().trim();
                } else if (event == XmlPullParser.START_TAG && "X_ScalarWebAPI_ActionList_URL".equals(tag)) {
                    base = xml.nextText().trim();
                } else if (event == XmlPullParser.END_TAG && "X_ScalarWebAPI_Service".equals(tag)
                        && "camera".equals(service) && base != null) {
                    return localUrl(base.replaceFirst("/+$", "") + "/camera", location.getHost());
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static URL localUrl(String raw, String expectedHost) throws IOException {
        URL url = new URL(raw);
        if (!"http".equals(url.getProtocol()) || !expectedHost.equals(url.getHost()) || url.getUserInfo() != null) {
            throw new IOException("カメラと同じ接続先のURLが必要です。");
        }
        return url;
    }

    private static HttpURLConnection open(CameraWifi wifi, URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) wifi.network.openConnection(url, Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(4500);
        return connection;
    }

    JSONArray call(String method, Object... args) throws Exception {
        JSONArray params = new JSONArray();
        for (Object arg : args) params.put(arg);
        int id = ids.incrementAndGet();
        byte[] body = new JSONObject().put("method", method).put("params", params).put("id", id)
                .put("version", "1.0").toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(wifi, endpoint);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            JSONObject response;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    data.write(buffer, 0, count);
                    if (data.size() > 65536) throw new IOException("カメラの応答が大きすぎます。");
                }
                response = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
            }
            JSONArray error = response.optJSONArray("error");
            if (error != null) throw new Rejected(method, error.optInt(0));
            if (response.optInt("id", -1) != id) throw new IOException("カメラの応答IDが一致しません。");
            return response.getJSONArray("result");
        } finally {
            connection.disconnect();
        }
    }

    String status() throws Exception {
        JSONArray event = call("getEvent", false);
        for (int i = 0; i < event.length(); i++) {
            JSONObject item = event.optJSONObject(i);
            if (item != null && item.has("cameraStatus")) return item.getString("cameraStatus");
        }
        throw new IOException("カメラの録画状態を取得できません。");
    }

    void prepareMovie() throws Exception {
        if (!"IDLE".equals(status())) return;
        JSONArray methods = call("getAvailableApiList").getJSONArray(0);
        if (!contains(methods, "startMovieRec") && contains(methods, "startRecMode")) call("startRecMode");
        if (!"movie".equals(call("getShootMode").getString(0))) call("setShootMode", "movie");
    }

    private static boolean contains(JSONArray values, String value) {
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) {
            if (value.equals(values.optString(i))) return true;
        }
        return false;
    }

    URL startPreview() throws Exception {
        return localUrl(call("startLiveview").getString(0), endpoint.getHost());
    }

    void stream(URL url, LiveFrames.Consumer consumer) throws IOException {
        HttpURLConnection connection = open(wifi, url);
        try (DataInputStream input = new DataInputStream(connection.getInputStream())) {
            LiveFrames.read(input, consumer);
        } finally {
            connection.disconnect();
        }
    }

    static final class Rejected extends IOException {
        Rejected(String method, int code) { super("AS300が " + method + " を受け付けませんでした（" + code + "）。"); }
    }
}
