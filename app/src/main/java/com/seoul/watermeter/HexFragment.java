package com.seoul.watermeter;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

public class HexFragment extends Fragment {

    private static final String EX_BASIC =
        "68 0F 0F 68 08 01 78 0F 06 00 00 21 00 1C 13 50 69 55 00 F4 16";
    private static final String EX_WARN =
        "68 0F 0F 68 08 01 78 0F 56 34 12 09 C4 1C 13 49 69 55 00 B1 16";

    private EditText etHex;
    private TextView tvResult;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_hex, vg, false);
        etHex    = v.findViewById(R.id.etHex);
        tvResult = v.findViewById(R.id.tvHexResult);

        v.findViewById(R.id.btnParse).setOnClickListener(x -> parse());
        v.findViewById(R.id.btnExBasic).setOnClickListener(x -> { etHex.setText(EX_BASIC); parse(); });
        v.findViewById(R.id.btnExWarn).setOnClickListener(x -> { etHex.setText(EX_WARN); parse(); });
        return v;
    }

    private void parse() {
        try {
            byte[] data = MeterProtocol.fromHex(etHex.getText().toString());
            MeterProtocol.ParseResult r = MeterProtocol.parseLongFrame(data);
            tvResult.setText(buildSpan(r));
        } catch (Exception e) {
            tvResult.setText("오류: " + e.getMessage());
        }
    }

    private SpannableStringBuilder buildSpan(MeterProtocol.ParseResult r) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int cyan   = Color.parseColor("#38BDF8");
        int green  = Color.parseColor("#34D399");
        int yellow = Color.parseColor("#FBBF24");
        int red    = Color.parseColor("#F87171");
        int muted  = Color.parseColor("#64748B");

        if (!r.ok) { color(sb, "✗ 오류: " + r.error + "\n", red); return sb; }

        color(sb, "══════════════════════\n", muted);
        color(sb, "검침값   ", muted);
        color(sb, r.readingFmt() + " ㎥\n", cyan);
        color(sb, "══════════════════════\n", muted);
        color(sb, "기물번호  ", muted); sb.append(r.meterNo + "\n");
        color(sb, "구경      ", muted); sb.append(r.diameter + " mm\n");
        color(sb, "소수점    ", muted); sb.append(r.decimals + "자리\n");
        color(sb, "주소      ", muted); sb.append(String.format("0x%02X\n", r.addr));
        color(sb, "══════════════════════\n", muted);
        color(sb, "상태      ", muted);
        color(sb, (r.hasWarning() ? "⚠ " + r.statusString() : "✓ 정상") + "\n",
            r.hasWarning() ? yellow : green);
        color(sb, "체크섬    ", muted);
        color(sb, (r.checksumOk ? "✓ 정상" : "✗ 오류") + "\n",
            r.checksumOk ? green : red);

        if (r.hasUdf) {
            color(sb, "══════════════════════\n", muted);
            color(sb, "프로토콜  ", muted); sb.append("V" + r.protoVer + "\n");
            color(sb, "검정월    ", muted); sb.append(r.verifyMonth + "월\n");
            color(sb, "제조사    ", muted); sb.append(r.mfrCode + "\n");
        }

        color(sb, "══════════════════════\n", muted);
        color(sb, r.rawHex + "\n", muted);
        return sb;
    }

    private void color(SpannableStringBuilder sb, String text, int c) {
        int s = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(c), s, sb.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
