package local.as300;

import java.io.DataInputStream;
import java.io.IOException;

final class LiveFrames {
    interface Consumer { void accept(byte[] jpeg); }

    static void read(DataInputStream input, Consumer consumer) throws IOException {
        byte[] common = new byte[8];
        byte[] payload = new byte[128];
        byte[] padding = new byte[255];
        while (!Thread.currentThread().isInterrupted()) {
            input.readFully(common);
            input.readFully(payload);
            int type = common[1] & 255;
            if ((common[0] & 255) != 255 || payload[0] != 0x24 || payload[1] != 0x35 ||
                    payload[2] != 0x68 || payload[3] != 0x79) {
                throw new IOException("ライブビューのデータ形式を認識できません。");
            }
            int size = (payload[4] & 255) << 16 | (payload[5] & 255) << 8 | payload[6] & 255;
            if (size > 4 * 1024 * 1024) throw new IOException("ライブビュー画像が大きすぎます。");
            byte[] data = new byte[size];
            input.readFully(data);
            input.readFully(padding, 0, payload[7] & 255);
            if (type == 1 && size > 0) consumer.accept(data);
        }
    }
}
