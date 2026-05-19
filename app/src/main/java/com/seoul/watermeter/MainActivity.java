package com.seoul.watermeter;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.seoul.watermeter.USB_PERMISSION";
    private static final String[] TAB_TITLES = {"검침", "HEX 파싱", "로그"};

    public static MainActivity instance;
    public final List<MeterProtocol.ParseResult> history = new ArrayList<>();

    private UsbManager       usbManager;
    private UsbSerialPort    serialPort;
    private TextView         tvConnStatus;

    private final ExecutorService readExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    private final Handler    mainHandler = new Handler(Looper.getMainLooper());
    private final Handler    autoHandler = new Handler(Looper.getMainLooper());
    private Runnable         autoRunnable;
    private volatile boolean isConnected = false;
    private volatile boolean isReading   = false;

    private ReadFragment readFragment;
    private HexFragment  hexFragment;
    private LogFragment  logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance     = this;
        usbManager   = (UsbManager) getSystemService(Context.USB_SERVICE);
        tvConnStatus = findViewById(R.id.tvConnStatus);
        setupViewPager();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        updateConnStatus("● 연결 안됨", R.color.muted, R.drawable.bg_pill_gray);
    }

    // ── 헤더 상태 표시 ────────────────────────────────────
    private void updateConnStatus(String text, int colorRes, int bgRes) {
        mainHandler.post(() -> {
            if (tvConnStatus == null) return;
            tvConnStatus.setText(text);
            tvConnStatus.setTextColor(getColor(colorRes));
            tvConnStatus.setBackgroundResource(bgRes);
        });
    }

    private void setupViewPager() {
        ViewPager2 vp = findViewById(R.id.viewPager);
        TabLayout  tl = findViewById(R.id.tabLayout);
        vp.setAdapter(new FragmentStateAdapter(this) {
            public int getItemCount() { return 3; }
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0: readFragment = new ReadFragment(); return readFragment;
                    case 1: hexFragment  = new HexFragment();  return hexFragment;
                    default: logFragment = new LogFragment();  return logFragment;
                }
            }
        });
        new TabLayoutMediator(tl, vp, (tab, pos) -> tab.setText(TAB_TITLES[pos])).attach();
    }

    // ── USB 연결 ──────────────────────────────────────────
    public void connectUsb() {
        // 즉시 로그 출력
        addLog("=== USB 연결 시도 ===", "INFO");

        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        addLog("발견된 드라이버 수: " + drivers.size(), "INFO");

        if (drivers.isEmpty()) {
            addLog("USB 장치 없음 — OTG 케이블 확인", "ERR");
            toast("USB 장치를 찾을 수 없습니다");
            return;
        }

        UsbDevice device = drivers.get(0).getDevice();
        addLog("USB: " + device.getProductName()
            + " VID=" + device.getVendorId()
            + " PID=" + device.getProductId(), "INFO");

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            addLog("USB 권한 요청 중...", "WARN");
            return;
        }
        addLog("권한 확인됨 — 포트 열기 시도", "INFO");
        openPort(drivers.get(0));
    }

    // ── 포트 열기 ─────────────────────────────────────────
    private void openPort(UsbSerialDriver driver) {
        readExecutor.execute(() -> {
            try {
                addLog("openPort() 진입", "INFO");

                UsbDeviceConnection conn =
                    usbManager.openDevice(driver.getDevice());
                if (conn == null) {
                    addLog("openDevice() 실패 — null 반환", "ERR");
                    return;
                }
                addLog("openDevice() 성공", "OK");

                UsbSerialPort port = driver.getPorts().get(0);
                port.open(conn);
                addLog("port.open() 성공", "OK");

                port.setParameters(
                    MeterProtocol.BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                );
                addLog("파라미터 설정: " + MeterProtocol.BAUD_RATE + " bps 8N1", "OK");

                port.setDTR(true);
                port.setRTS(true);
                addLog("DTR/RTS High 설정", "OK");

                Thread.sleep(50);

                serialPort  = port;
                isConnected = true;

                updateConnStatus("● 연결됨", R.color.yellow, R.drawable.bg_pill_gray);
                mainHandler.post(() -> {
                    if (readFragment != null) readFragment.onConnected(true);
                });
                addLog("=== 연결 완료 — 검침 요청 전송 버튼을 누르세요 ===", "OK");

                startReadLoop();

            } catch (IOException | InterruptedException e) {
                addLog("openPort 오류: " + e.getMessage(), "ERR");
            }
        });
    }

    // ── 연결 해제 ─────────────────────────────────────────
    public void disconnectUsb() {
        stopAutoTimer();
        isReading   = false;
        isConnected = false;
        UsbSerialPort p = serialPort;
        serialPort = null;
        if (p != null) {
            try { p.close(); } catch (IOException ignored) {}
        }
        updateConnStatus("● 연결 안됨", R.color.muted, R.drawable.bg_pill_gray);
        mainHandler.post(() -> {
            if (readFragment != null) readFragment.onConnected(false);
        });
        addLog("=== 연결 해제 ===", "WARN");
    }

    // ── 수신 루프 ─────────────────────────────────────────
    private void startReadLoop() {
        isReading = true;
        byte[] buf    = new byte[256];
        byte[] acc    = new byte[512];
        int[]  accLen = {0};

        addLog("수신 루프 시작", "INFO");

        while (isReading && serialPort != null) {
            try {
                int n = serialPort.read(buf, 200);
                if (n > 0) {
                    addLog("← 수신 " + n + "바이트", "HEX");
                    System.arraycopy(buf, 0, acc, accLen[0], n);
                    accLen[0] += n;

                    // 수신 HEX 로그
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < n; i++)
                        hex.append(String.format("%02X ", buf[i] & 0xFF));
                    addLog("HEX: " + hex.toString().trim(), "HEX");

                    int end = MeterProtocol.findLongFrameEnd(acc, accLen[0]);
                    if (end > 0) {
                        byte[] frame = new byte[end];
                        System.arraycopy(acc, 0, frame, 0, end);
                        accLen[0] -= end;
                        System.arraycopy(acc, end, acc, 0, accLen[0]);
                        MeterProtocol.ParseResult r =
                            MeterProtocol.parseLongFrame(frame);
                        mainHandler.post(() -> handleResult(r));
                    }
                    if (accLen[0] > 400) accLen[0] = 0;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                addLog("read 오류: " + msg, "ERR");
                if (msg != null && (msg.contains("Broken pipe")
                        || msg.contains("closed"))) {
                    mainHandler.post(this::disconnectUsb);
                    break;
                }
            }
        }
        addLog("수신 루프 종료", "WARN");
    }

    // ── 검침 요청 전송 ─────────────────────────────────────
    public void sendRequest(int addr) {
        // 버튼 클릭 즉시 로그 출력
        addLog("=== 검침 요청 버튼 클릭 (주소:" + addr + ") ===", "INFO");
        addLog("isConnected=" + isConnected + " serialPort=" + (serialPort != null ? "있음" : "null"), "INFO");

        if (!isConnected || serialPort == null) {
            addLog("연결 안됨 — 전송 취소", "ERR");
            toast("먼저 연결하세요");
            return;
        }

        byte[] frame = MeterProtocol.buildRequest(addr);
        addLog("프레임: " + MeterProtocol.toHex(frame), "HEX");
        addLog("writeExecutor에 전송 작업 등록...", "INFO");

        writeExecutor.execute(() -> {
            addLog("writeExecutor 실행 시작", "INFO");
            try {
                serialPort.setRTS(true);
                serialPort.setDTR(true);
                Thread.sleep(35);

                addLog("write() 호출 중...", "INFO");
                serialPort.write(frame, 3000);
                addLog("→ REQ_UD2 전송 완료: " + MeterProtocol.toHex(frame), "OK");

                Thread.sleep(100);

            } catch (IOException | InterruptedException e) {
                addLog("전송 실패: " + e.getMessage(), "ERR");
            }
        });
    }

    // ── 자동 반복 ─────────────────────────────────────────
    public void setAutoInterval(int ms, int addr) {
        stopAutoTimer();
        if (ms > 0) {
            autoRunnable = () -> {
                if (isConnected) sendRequest(addr);
                autoHandler.postDelayed(autoRunnable, ms);
            };
            autoHandler.postDelayed(autoRunnable, ms);
            addLog("자동 검침: " + (ms / 1000) + "초 간격", "INFO");
        }
    }

    public void stopAutoTimer() {
        if (autoRunnable != null) {
            autoHandler.removeCallbacks(autoRunnable);
            autoRunnable = null;
        }
    }

    // ── 결과 처리 ─────────────────────────────────────────
    public void handleResult(MeterProtocol.ParseResult r) {
        if (!r.ok) { addLog("파싱 오류: " + r.error, "ERR"); return; }
        history.add(0, r);
        updateConnStatus("● 검침 완료", R.color.green, R.drawable.bg_pill_green);
        if (readFragment != null) readFragment.updateReading(r);
        addLog("✓ 검침값: " + r.meterNo + " = " + r.readingFmt() + " ㎥ | "
            + r.statusString() + (r.checksumOk ? "" : " | 체크섬오류"),
            r.hasWarning() ? "WARN" : "OK");
    }

    // ── 로그 ─────────────────────────────────────────────
    public void addLog(String msg, String level) {
        // UI 스레드가 아닌 경우에도 안전하게 처리
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (logFragment != null) logFragment.addLog(msg, level);
        } else {
            mainHandler.post(() -> {
                if (logFragment != null) logFragment.addLog(msg, level);
            });
        }
    }

    public boolean isConnected() { return isConnected; }

    private void toast(String m) {
        mainHandler.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    // ── USB BroadcastReceiver ─────────────────────────────
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
                addLog("USB 권한 응답: " + (granted ? "허용" : "거부"), granted ? "OK" : "ERR");
                if (granted) {
                    List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!drivers.isEmpty()) openPort(drivers.get(0));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                addLog("USB 장치 연결됨", "INFO");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                addLog("USB 장치 분리됨", "WARN");
                if (isConnected) disconnectUsb();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectUsb();
        readExecutor.shutdown();
        writeExecutor.shutdown();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        instance = null;
    }
}
