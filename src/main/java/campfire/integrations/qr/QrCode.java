package campfire.integrations.qr;

import io.nayuki.qrcodegen.DataTooLongException;

/// The QR code SVG for `QrCodeController#show` (reference/app/controllers/qr_code_controller.rb):
/// error correction level H, which rqrcode defaults to, and dark modules on white without a
/// quiet zone, as `as_svg(viewbox: true, fill: :white, color: :black)` draws them.
public final class QrCode {
    private QrCode() {}

    /// The SVG for `data` (a URL's bytes), or null when it doesn't fit a version 40 code.
    public static String svg(byte[] data) {
        io.nayuki.qrcodegen.QrCode code;
        try {
            code = io.nayuki.qrcodegen.QrCode.encodeBinary(data, io.nayuki.qrcodegen.QrCode.Ecc.HIGH);
        } catch (DataTooLongException e) {
            return null;
        }
        var svg = new StringBuilder(256 + code.size * code.size * 6);
        svg.append("<?xml version=\"1.0\" standalone=\"yes\"?><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(code.size).append(' ')
            .append(code.size).append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"");
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (code.getModule(x, y)) svg.append('M').append(x).append(',').append(y).append("h1v1h-1z");
            }
        }
        return svg.append("\"/></svg>").toString();
    }
}
