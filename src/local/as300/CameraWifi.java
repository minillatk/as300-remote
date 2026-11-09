package local.as300;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

@SuppressWarnings("deprecation")
final class CameraWifi implements AutoCloseable {
    final WifiManager wifi;
    private final ConnectivityManager connections;
    private int added = -1;
    private int previous = -1;
    private int selected = -1;
    private boolean closed;
    Network network;
    InetAddress local;
    InetAddress gateway;

    CameraWifi(Context context) {
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connections = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void connect(WifiQr qr) throws Exception {
        if (!wifi.isWifiEnabled()) throw new IOException("スマホのWi-Fiをオンにしてください。");
        WifiInfo initial = wifi.getConnectionInfo();
        String target = qr == null ? ssid(initial) : qr.ssid;
        if (!WifiQr.isAs300(target)) throw new IOException("スマホのWi-Fi設定からAS300を選んでください。");
        if (!target.equals(ssid(initial))) select(qr, initial);

        long deadline = SystemClock.elapsedRealtime() + 22000;
        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted()) {
            WifiInfo active = wifi.getConnectionInfo();
            if (active != null && target.equals(ssid(active)) && active.getIpAddress() != 0) {
                InetAddress address = ipv4(active.getIpAddress());
                Network route = wifiNetwork(address);
                if (route != null) {
                    network = route;
                    local = address;
                    int router = wifi.getDhcpInfo() == null ? 0 : wifi.getDhcpInfo().gateway;
                    gateway = router == 0 ? null : ipv4(router);
                    return;
                }
            }
            Thread.sleep(350);
        }
        throw new IOException("AS300のWi-Fiに接続できません。カメラのWi-Fi設定と撮影モードを確認してください。");
    }

    private synchronized void select(WifiQr qr, WifiInfo initial) throws IOException {
        if (closed) throw new IOException("接続を中止しました。");
        previous = initial == null ? -1 : initial.getNetworkId();
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quoted(qr.ssid);
        config.hiddenSSID = qr.hidden;
        config.preSharedKey = qr.password.matches("[0-9a-fA-F]{64}") ? qr.password : quoted(qr.password);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        if (saved != null) {
            for (WifiConfiguration entry : saved) {
                if (config.SSID.equals(entry.SSID)) selected = entry.networkId;
            }
        }
        if (selected < 0) {
            added = wifi.addNetwork(config);
            selected = added;
        }
        if (selected < 0 || !wifi.enableNetwork(selected, true)) {
            throw new IOException("Wi-Fi設定からAS300に接続し、「接続済みのAS300を開く」を押してください。");
        }
    }

    private Network wifiNetwork(InetAddress expected) {
        for (Network candidate : connections.getAllNetworks()) {
            NetworkCapabilities capabilities = connections.getNetworkCapabilities(candidate);
            LinkProperties properties = connections.getLinkProperties(candidate);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || properties == null) continue;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress().equals(expected)) return candidate;
            }
        }
        return null;
    }

    private static InetAddress ipv4(int value) throws IOException {
        return InetAddress.getByAddress(new byte[]{(byte) value, (byte) (value >> 8),
                (byte) (value >> 16), (byte) (value >> 24)});
    }

    private static String ssid(WifiInfo info) {
        String value = info == null ? null : info.getSSID();
        if (value == null) return "";
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (added >= 0) wifi.removeNetwork(added);
        if (previous >= 0 && selected >= 0 && previous != selected) wifi.enableNetwork(previous, true);
    }
}
