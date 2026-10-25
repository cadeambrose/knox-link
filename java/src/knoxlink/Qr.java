package knoxlink;

import java.util.HashMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** QR codes through the ZXing copy the game already ships (it uses it for two-factor login). */
final class Qr {
    private Qr() {}

    static String[] rows(String text) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            String[] out = new String[m.getHeight()];
            StringBuilder sb = new StringBuilder(m.getWidth());
            for (int y = 0; y < m.getHeight(); y++) {
                sb.setLength(0);
                for (int x = 0; x < m.getWidth(); x++) sb.append(m.get(x, y) ? '1' : '0');
                out[y] = sb.toString();
            }
            return out;
        } catch (Throwable t) {   // NoClassDefFoundError if a game update drops ZXing
            return null;
        }
    }
}
