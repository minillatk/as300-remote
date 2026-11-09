package local.as300;

import com.google.zxing.client.result.WifiParsedResult;
import com.google.zxing.client.result.WifiResultParser;
import com.google.zxing.Result;
import com.google.zxing.BarcodeFormat;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WifiQr {
    private static final Pattern SONY_MODEL = Pattern.compile("(?:^|;)C:([A-Za-z0-9_-]+)(?:;|$)");
    final String ssid;
    final String password;
    final boolean hidden;

    private WifiQr(String ssid, String password, boolean hidden) {
        this.ssid = ssid;
        this.password = password;
        this.hidden = hidden;
    }

    // Sony W01 stores the DIRECT suffix separately from the camera name.
    static WifiQr parse(String text) {
        String value = text.trim();
        boolean sony = value.startsWith("W01:");
        String model = null;
        if (sony) {
            Matcher field = SONY_MODEL.matcher(value.substring(4));
            if (field.find()) model = field.group(1);
            value = "WIFI:" + value.substring(4);
        }
        WifiParsedResult wifi = new WifiResultParser().parse(
                new Result(value, null, null, BarcodeFormat.QR_CODE));
        if (wifi == null || wifi.getPassword() == null) return null;
        if (!sony && !wifi.getNetworkEncryption().startsWith("WPA")) return null;
        String ssid = wifi.getSsid();
        if (sony && !ssid.startsWith("DIRECT-") && model != null) {
            ssid = "DIRECT-" + ssid + ":" + model;
        }
        String password = wifi.getPassword();
        if (!isAs300(ssid) || ssid.getBytes(StandardCharsets.UTF_8).length > 32 ||
                !password.matches("[ -~]{8,63}|[0-9a-fA-F]{64}")) return null;
        return new WifiQr(ssid, password, wifi.isHidden());
    }

    static boolean isAs300(String ssid) {
        return ssid.startsWith("DIRECT-") && ssid.toUpperCase(Locale.ROOT).contains("AS300");
    }
}
