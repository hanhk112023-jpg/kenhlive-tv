package com.kenhlive.testlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.Toast;

/**
 * Launcher riêng cho CI test (UI-Tour / QA / Porthole).
 *
 * Vấn đề nó giải quyết: emulator Android TV mặc định dùng Google TV launcher,
 * trên đó có sẵn YouTube. Khi app KenhLive thoát ra màn hình chính giữa
 * chừng (lỗi luồng, BACK...), script test vẫn bấm D-pad mù và vô tình mở
 * YouTube ("not supported on this device") — video test quay ra màn hình
 * YouTube thay vì app.
 *
 * Launcher này là HOME duy nhất của máy test: chỉ có 1 nút mở KenhLive,
 * BACK không thoát (ở lại đây), nên test không bao giờ lạc sang app khác.
 * App KenhLive thoát ra sẽ rơi về đây và script có thể phát hiện.
 */
public class TestLauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_launcher);
        Button btn = findViewById(R.id.btnOpen);
        btn.setOnClickListener(v -> openKenhLive());
        btn.requestFocus();
    }

    private void openKenhLive() {
        Intent intent = new Intent();
        intent.setClassName("com.kenhlive.tv", "com.kenhlive.tv.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Khong mo duoc KenhLive: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** BACK ở launcher test: nuốt lại, không thoát ra launcher hệ thống. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
