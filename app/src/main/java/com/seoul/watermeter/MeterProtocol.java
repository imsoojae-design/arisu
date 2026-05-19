package com.seoul.watermeter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 서울특별시 디지털계량기 프로토콜 V1.2 파서
 * 검침값: 8 digit BCD, Little-Endian
 */
public class MeterProtocol {

    public static final int LONG_START  = 0x68;
    public static final int SHORT_START = 0x10;
    public static final int STOP_BYTE   = 0x16;
    public static final int C_REQ_UD2   = 0x5B;
    public static final int BAUD_RATE   = 1200;

    private static final int[] DIAMETER = {
        0, 15, 20, 25, 32, 40, 50, 80, 100, 150, 200, 250, 300
    };

    // ── 검침 요청 Short Frame 생성 ──────────────────────────
    public static byte[] buildRequest(int addr) {
        int c = C_REQ_UD2;
        int a = addr & 0xFF;
        int chk = (c + a) & 0xFF;
        return new byte[]{
            (byte) SHORT_START, (byte) c, (byte) a, (byte) chk, (byte) STOP_BYTE
        };
    }

    // ── Long Frame 파싱 ────────────────────────────────────
    public static ParseResult parseLongFrame(byte[] data) {
        ParseResult r = new ParseResult();
        r.rawHex = toHex(data);
        r.timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        if (data.length < 14) { r.error = "길이 부족: " + data.length + "B"; return r; }
        if ((data[0] & 0xFF) != LONG_START || (data[3] & 0xFF) != LONG_START) {
            r.error = "Start 바이트 오류"; return r;
        }
        if ((data[data.length - 1] & 0xFF) != STOP_BYTE) { r.error = "Stop 바이트 오류"; return r; }

        r.addr = data[5] & 0xFF;

        // User Data: data[7] ~ data[length-3]
        int udLen = data.length - 9;
        if (udLen < 11) { r.error = "UserData 짧음"; return r; }
        byte[] ud = new byte[udLen];
        System.arraycopy(data, 7, ud, 0, udLen);

        // 체크섬
        int calcChk = 0;
        for (int i = 4; i < data.length - 2; i++) calcChk += (data[i] & 0xFF);
        r.checksumOk = ((calcChk & 0xFF) == (data[data.length - 2] & 0xFF));

        // 기물번호 (4바이트 LE BCD)
        r.meterNo = parseMeterNo(new byte[]{ud[1], ud[2], ud[3], ud[4]});

        // 상태 바이트
        int status = ud[5] & 0xFF;
        r.q3Exceed = (status & 0x80) != 0;
        r.reverse  = (status & 0x40) != 0;
        r.leak     = (status & 0x20) != 0;
        r.battLow  = (status & 0x04) != 0;

        // DIF / VIF
        int dif = ud[6] & 0xFF;
        int vif = ud[7] & 0xFF;
        r.decimals = vif & 0x0F;
        int di = (dif >> 4) & 0xF;
        r.diameter = (di < DIAMETER.length) ? DIAMETER[di] : 0;

        // 검침값 (4바이트 LE BCD) ← BCD 파싱
        r.reading = parseBcd(new byte[]{ud[8], ud[9], ud[10], ud[11]}, r.decimals);

        // UDF (선택)
        if (ud.length >= 16) {
            int pv = ud[12] & 0xFF;
            r.protoVer = ((pv >> 4) & 0xF) + "." + (pv & 0xF);
            int vm = ud[13] & 0xFF;
            r.verifyMonth = (vm >> 4) * 10 + (vm & 0xF);
            int mc = ud[15] & 0xFF;
            r.mfrCode = (mc >= 32 && mc < 127) ? String.valueOf((char) mc) : "?";
            r.hasUdf = true;
        }

        r.ok = true;
        return r;
    }

    // ── BCD 검침값 파싱 ────────────────────────────────────
    private static double parseBcd(byte[] data, int decimals) {
        StringBuilder sb = new StringBuilder();
        for (int i = data.length - 1; i >= 0; i--)
            sb.append(String.format("%02X", data[i] & 0xFF));
        try {
            return Long.parseLong(sb.toString()) / Math.pow(10, decimals);
        } catch (NumberFormatException e) { return 0.0; }
    }

    // ── 기물번호 파싱 ──────────────────────────────────────
    private static String parseMeterNo(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = data.length - 1; i >= 0; i--)
            sb.append(String.format("%02X", data[i] & 0xFF));
        String s = sb.toString();
        return s.substring(0, 2) + "-" + s.substring(2);
    }

    // ── HEX 변환 유틸 ─────────────────────────────────────
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String[] parts = hex.trim().replaceAll("[,\\s]+", " ").split(" ");
        byte[] r = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            r[i] = (byte) Integer.parseInt(parts[i].trim(), 16);
        return r;
    }

    // ── 수신 버퍼에서 Long Frame 추출 ─────────────────────
    public static int findLongFrameEnd(byte[] buf, int len) {
        for (int i = 0; i < len - 5; i++) {
            if ((buf[i] & 0xFF) == LONG_START
                    && i + 3 < len
                    && (buf[i + 3] & 0xFF) == LONG_START) {
                int lField   = buf[i + 1] & 0xFF;
                int expected = lField + 6;
                if (i + expected <= len
                        && (buf[i + expected - 1] & 0xFF) == STOP_BYTE) {
                    return i + expected;
                }
            }
        }
        return -1;
    }

    // ── 파싱 결과 ─────────────────────────────────────────
    public static class ParseResult {
        public boolean ok = false;
        public String  error = "", timestamp = "", rawHex = "";
        public int     addr = 0, diameter = 0, decimals = 3, verifyMonth = 0;
        public double  reading = 0;
        public String  meterNo = "—", protoVer = "", mfrCode = "";
        public boolean q3Exceed, reverse, leak, battLow, checksumOk, hasUdf;

        public String statusString() {
            if (!q3Exceed && !reverse && !leak && !battLow) return "정상";
            StringBuilder sb = new StringBuilder();
            if (q3Exceed) sb.append("Q3초과 ");
            if (reverse)  sb.append("역류 ");
            if (leak)     sb.append("누수 ");
            if (battLow)  sb.append("배터리낮음");
            return sb.toString().trim();
        }

        public boolean hasWarning() { return q3Exceed || reverse || leak || battLow; }

        public String readingFmt() {
            return String.format(Locale.getDefault(), "%,." + decimals + "f", reading);
        }

        public String[] toCsvRow() {
            return new String[]{
                timestamp, meterNo, String.valueOf(diameter),
                String.valueOf(decimals), readingFmt(),
                statusString(), checksumOk ? "정상" : "오류",
                protoVer, hasUdf ? verifyMonth + "월" : "", mfrCode, rawHex
            };
        }
    }
}
