package dev.local.videoinject;

import android.util.Base64;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds PID and PidData XML blocks for face biometric authentication.
 *
 * Inner Pid: face JPEG as base64 inside Bio element (ISO 19794-5).
 * Outer PidData: Resp with qScore, DeviceInfo, Skey, Hmac, encrypted Data.
 *
 * In production, crypto uses certified device keys. This builds the
 * correct XML structure with placeholder crypto values.
 */
final class PidBlockBuilder {

    private static final String TAG = "PidBlock";
    private static final SimpleDateFormat ISO_FORMAT;

    static {
        ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    static final class PidResult {
        final String pidXml;
        final String pidDataXml;
        final String timestamp;
        final int qualityScore;
        final String faceBase64;
        final int jpegSizeBytes;

        PidResult(String pidXml, String pidDataXml, String timestamp,
                  int qualityScore, String faceBase64, int jpegSizeBytes) {
            this.pidXml = pidXml;
            this.pidDataXml = pidDataXml;
            this.timestamp = timestamp;
            this.qualityScore = qualityScore;
            this.faceBase64 = faceBase64;
            this.jpegSizeBytes = jpegSizeBytes;
        }
    }

    private PidBlockBuilder() {}

    static PidResult build(byte[] faceJpegBytes, int qualityScore, int imageWidth, int imageHeight) {
        String timestamp = ISO_FORMAT.format(new Date());
        String faceBase64 = Base64.encodeToString(faceJpegBytes, Base64.NO_WRAP);

        String pidXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Pid ts=\"" + timestamp + "\" ver=\"2.0\">\n"
                + "  <Bio type=\"FACE\" posh=\"FACE\" bs=\"ISO19794-5:2011\">" + faceBase64 + "</Bio>\n"
                + "</Pid>";

        String placeholderSkey = Base64.encodeToString(
                "DEMO_SESSION_KEY_WOULD_BE_AES256_ENCRYPTED_WITH_UIDAI_PUBLIC_KEY".getBytes(),
                Base64.NO_WRAP);
        String placeholderHmac = Base64.encodeToString(
                "DEMO_HMAC_SHA256_OF_PID_XML_BYTES".getBytes(),
                Base64.NO_WRAP);
        String placeholderEncryptedPid = Base64.encodeToString(
                pidXml.getBytes(), Base64.NO_WRAP);

        String pidDataXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<PidData>\n"
                + "  <Resp errCode=\"0\" errInfo=\"Success\"\n"
                + "        fCount=\"0\" fType=\"\" iCount=\"0\" iType=\"\"\n"
                + "        pCount=\"0\" pType=\"\" nmPoints=\"0\"\n"
                + "        qScore=\"" + qualityScore + "\" />\n"
                + "  <DeviceInfo dpId=\"TESTVENDOR.FACE.001\"\n"
                + "              rdsId=\"TESTVENDOR.RD.001\" rdsVer=\"1.0.0\"\n"
                + "              dc=\"TEST_DEVICE_CODE\" mi=\"TEST_MODEL_ID\"\n"
                + "              mc=\"TEST_MGMT_CENTER\" />\n"
                + "  <Skey ci=\"20271231\">" + placeholderSkey + "</Skey>\n"
                + "  <Hmac>" + placeholderHmac + "</Hmac>\n"
                + "  <Data type=\"X\">" + placeholderEncryptedPid + "</Data>\n"
                + "</PidData>";

        Log.i(TAG, "PID block built: ts=" + timestamp + " qScore=" + qualityScore
                + " jpeg=" + faceJpegBytes.length + "B base64=" + faceBase64.length() + "chars");

        return new PidResult(pidXml, pidDataXml, timestamp, qualityScore, faceBase64, faceJpegBytes.length);
    }
}
