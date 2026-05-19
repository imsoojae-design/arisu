package com.seoul.watermeter;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogFragment extends Fragment {

    private TextView              tvLog;
    private ScrollView            scrollLog;
    private SpannableStringBuilder logBuf    = new SpannableStringBuilder();
    private int                   lineCount  = 0;
    private static final int      MAX_LINES  = 300;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_log, vg, false);
        tvLog     = v.findViewById(R.id.tvLog);
        scrollLog = v.findViewById(R.id.scrollLog);

        v.findViewById(R.id.btnClearLog).setOnClickListener(x -> {
            logBuf    = new SpannableStringBuilder();
            lineCount = 0;
            tvLog.setText("");
        });

        if (logBuf.length() > 0) tvLog.setText(logBuf);
        return v;
    }

    public void addLog(String msg, String level) {
        int color;
        switch (level) {
            case "OK":   color = Color.parseColor("#34D399"); break;
            case "WARN": color = Color.parseColor("#FBBF24"); break;
            case "ERR":  color = Color.parseColor("#F87171"); break;
            case "HEX":  color = Color.parseColor("#38BDF8"); break;
            default:     color = Color.parseColor("#94A3B8"); break;
        }

        String ts   = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";

        int start = logBuf.length();
        logBuf.append(line);
        logBuf.setSpan(new ForegroundColorSpan(color), start, logBuf.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (++lineCount > MAX_LINES) {
            for (int i = 0; i < logBuf.length(); i++) {
                if (logBuf.charAt(i) == '\n') {
                    logBuf.delete(0, i + 1);
                    lineCount--;
                    break;
                }
            }
        }

        if (tvLog != null) {
            tvLog.setText(logBuf);
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        }
    }
}
