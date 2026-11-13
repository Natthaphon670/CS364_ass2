package com.example.stepbystep;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private TextView tvCurrentSteps;
    private boolean isSensorPresent;

    // ตัวแปรสำหรับคำนวณก้าว (Session Delta)
    private int stepCount = 0;
    private float initialSteps = -1;
    private float lastRawSteps = -1; // เก็บค่าดิบล่าสุดจากเซนเซอร์ไว้ใช้ตอนกดบันทึก/รีเซ็ต

    // ตัวแปรสำหรับการเขย่า
    private Sensor accelerometerSensor;
    private TextView tvCurrentShakes;
    private boolean isAccelerometerPresent;
    private int shakeCount = 0;
    private float acceleration = 0f;
    private float currentAcceleration = SensorManager.GRAVITY_EARTH;
    private float lastAcceleration = SensorManager.GRAVITY_EARTH;
    private long lastShakeTime = 0;
    private static final int SHAKE_THRESHOLD = 12;

    // --- UI และตัวแปรสำหรับข้อ 3 (Session Delta) และ ข้อ 4 (Duration & Average) ---
    private TextView tvDurationRate, tvStepsPerMin, tvShakesPerMin, tvCumulativeHistory;
    private MaterialButton btnSaveActivity;

    private long sessionStartTime = 0L;

    // --- ข้อ 5 (Total & New Record) และ ข้อ 6 (Insert Session) ---
    private DBHelper dbHelper;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    // --- ข้อ 7 (Query & Persistence) และ ข้อ 8 (Delete & Recalculate) ---
    private RecyclerView rvActivityHistory;
    private ActivityAdapter activityAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // เชื่อมต่อ UI ตาม ID ใน activity_main.xml
        tvCurrentSteps = findViewById(R.id.tvCurrentSteps);
        tvCurrentShakes = findViewById(R.id.tvCurrentShakes);
        tvDurationRate = findViewById(R.id.tvDurationRate);
        tvStepsPerMin = findViewById(R.id.tvStepsPerMin);
        tvShakesPerMin = findViewById(R.id.tvShakesPerMin);
        tvCumulativeHistory = findViewById(R.id.tvCumulativeHistory);
        btnSaveActivity = findViewById(R.id.btnSaveActivity);

        // ข้อ 6: เปิดฐานข้อมูล SQLite
        dbHelper = new DBHelper(this);

        // ข้อ 7: ตั้งค่า RecyclerView แสดงประวัติกิจกรรม (กดการ์ดเพื่อดูรายละเอียด/ลบ)
        rvActivityHistory = findViewById(R.id.rvActivityHistory);
        rvActivityHistory.setLayoutManager(new LinearLayoutManager(this));
        activityAdapter = new ActivityAdapter(this::showItemDialog);
        rvActivityHistory.setAdapter(activityAdapter);

        // ข้อ 7 + ข้อ 5: โหลดประวัติและยอดรวมจากฐานข้อมูลทุกครั้งที่เปิดแอป
        refreshAll();

        // ขอสิทธิ์ ACTIVITY_RECOGNITION (จำเป็นสำหรับ Android 10 ขึ้นไป)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 1);
            }
        }

        // กำหนดค่า SensorManager และตรวจสอบ TYPE_STEP_COUNTER
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) {
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            isSensorPresent = true;
        } else {
            tvCurrentSteps.setText("-");
            Toast.makeText(this, "เซนเซอร์นับก้าวไม่รองรับในอุปกรณ์นี้", Toast.LENGTH_LONG).show();
            isSensorPresent = false;
        }

        // ตรวจสอบเซนเซอร์ตรวจจับการเคลื่อนไหว
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            isAccelerometerPresent = true;
        } else {
            tvCurrentShakes.setText("-");
            Toast.makeText(this, "เซนเซอร์ตรวจจับการเคลื่อนไหวไม่รองรับในอุปกรณ์นี้", Toast.LENGTH_LONG).show();
            isAccelerometerPresent = false;
        }

        // ตั้งค่าตัวจับเวลาอัปเดตทุกๆ 1 วินาที (ข้อ 4: Duration & Average)
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedMillis = System.currentTimeMillis() - sessionStartTime;
                updateDurationAndAverage(elapsedMillis);
                timerHandler.postDelayed(this, 1000);
            }
        };

        // เริ่มต้นเซสชันแรกทันทีที่เปิดแอป
        startNewSession();

        // เมื่อกดปุ่ม "บันทึกกิจกรรม" -> บันทึกลง SQLite (ข้อ 6) อัปเดตยอดรวม/สถิติ (ข้อ 5) แล้วเริ่มเซสชันใหม่ (ข้อ 3)
        btnSaveActivity.setOnClickListener(v -> saveCurrentSession());
    }

    // =====================================================================
    // ข้อ 6: Insert Session + ข้อ 5: Total & New Record
    // =====================================================================
    private void saveCurrentSession() {
        // snapshot ค่าของเซสชันก่อน เพราะ onSensorChanged อาจอัปเดตตัวแปรระหว่างทำงาน
        final int steps = stepCount;
        final int shakes = shakeCount;
        final long endTime = System.currentTimeMillis();
        final long durationMs = Math.max(0L, endTime - sessionStartTime);

        if (steps <= 0 && shakes <= 0) {
            Toast.makeText(this, "ยังไม่มีก้าวหรือการเขย่าในกิจกรรมนี้", Toast.LENGTH_SHORT).show();
            return;
        }

        // ค่าเฉลี่ยต่อนาที ใช้สูตรเดียวกับ updateDurationAndAverage() (ต่ำกว่า 1 วินาทีถือเป็น 0)
        double minutes = durationMs / 60000.0;
        double stepsPerMin = durationMs >= 1000 ? steps / minutes : 0.0;
        double shakesPerMin = durationMs >= 1000 ? shakes / minutes : 0.0;

        DBHelper.InsertResult result = dbHelper.insertSession(
                sessionStartTime, endTime, durationMs, steps, shakes, stepsPerMin, shakesPerMin);

        if (!result.isSuccess()) {
            // insert ล้มเหลว: ไม่รีเซ็ตเซสชัน เพื่อให้ผู้ใช้กดบันทึกซ้ำได้โดยข้อมูลไม่หาย
            Toast.makeText(this, "บันทึกกิจกรรมไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return;
        }

        // ข้อ 5/7: โหลดรายการประวัติและยอดรวมใหม่จากฐานข้อมูล
        refreshAll();

        StringBuilder msg = new StringBuilder(String.format(Locale.getDefault(),
                "บันทึกกิจกรรมแล้ว: %d ก้าว, %d เขย่า", steps, shakes));
        if (result.isNewStepRecord) msg.append("\n").append(getString(R.string.new_step_record));
        if (result.isNewShakeRecord) msg.append("\n").append(getString(R.string.new_shake_record));
        Toast.makeText(this, msg.toString(),
                (result.isNewStepRecord || result.isNewShakeRecord) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();

        // รีเซ็ตค่าทั้งหมดเพื่อเริ่มนับ Session ใหม่ (Delta ใหม่)
        startNewSession();
    }

    // =====================================================================
    // ข้อ 7: Query & Persistence
    // =====================================================================

    // โหลดรายการประวัติ + ยอดรวมจากฐานข้อมูล (ใช้ฐานข้อมูลเป็นแหล่งข้อมูลหลักเสมอ)
    private void refreshAll() {
        activityAdapter.submit(dbHelper.getAllSessions());
        refreshTotals();
    }

    // ข้อ 5: แสดงยอดรวมก้าว/เขย่าจากประวัติทั้งหมดในฐานข้อมูล
    private void refreshTotals() {
        int[] totals = dbHelper.getTotals();
        tvCumulativeHistory.setText(String.format(Locale.getDefault(),
                "%d ก้าว • %d เขย่า", totals[0], totals[1]));
    }

    // =====================================================================
    // ข้อ 8: Delete & Recalculate
    // =====================================================================

    // กดการ์ดประวัติ -> แสดงรายละเอียด พร้อมปุ่ม ลบ / ปิด
    private void showItemDialog(SessionRecord r) {
        long totalSec = r.durationMs / 1000;
        String message = String.format(Locale.getDefault(),
                "%d steps • %d shakes\nDuration: %02d:%02d\n%.1f steps/min • %.1f shakes/min",
                r.steps, r.shakes, totalSec / 60, totalSec % 60,
                r.stepsPerMin, r.shakesPerMin);

        new AlertDialog.Builder(this)
                .setTitle(R.string.activity_history_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_delete, (d, w) -> deleteSession(r))
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    // ลบจากฐานข้อมูลจริง แล้วโหลดรายการ ยอดรวม และป้ายสถิติใหม่ (คำนวณใหม่ใน DBHelper)
    private void deleteSession(SessionRecord r) {
        if (dbHelper.deleteSession(r.id)) {
            refreshAll();
            Toast.makeText(this, "ลบกิจกรรมแล้ว", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "ลบกิจกรรมไม่สำเร็จ", Toast.LENGTH_LONG).show();
        }
    }

    // ฟังก์ชันเริ่ม/รีเซ็ตเซสชันใหม่ (ข้อ 3: Session Delta)
    private void startNewSession() {
        // ตั้งค่าจุดอ้างอิงก้าวใหม่ให้เท่ากับค่าล่าสุดจากเซนเซอร์ (ถ้าเคยรับค่ามาแล้ว) หรือ -1 เพื่อรอรับค่าแรก
        initialSteps = lastRawSteps;
        stepCount = 0;
        shakeCount = 0;
        sessionStartTime = System.currentTimeMillis();

        // รีเซ็ตการแสดงผลบนหน้าจอ
        if (isSensorPresent) tvCurrentSteps.setText("0");
        if (isAccelerometerPresent) tvCurrentShakes.setText("0");
        tvDurationRate.setText("00:00");
        tvStepsPerMin.setText("0");
        tvShakesPerMin.setText("0");

        // รีสตาร์ทตัวจับเวลา
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
    }

    // ฟังก์ชันคำนวณเวลาและค่าเฉลี่ยต่อนาที (ข้อ 4: Duration & Average)
    private void updateDurationAndAverage(long elapsedMillis) {
        int totalSeconds = (int) (elapsedMillis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;

        // แสดงเวลา MM:SS
        tvDurationRate.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));

        // คำนวณค่าเฉลี่ยต่อนาที (ให้เริ่มคำนวณเมื่อผ่านไปอย่างน้อย 1 วินาที เพื่อไม่ให้ตัวเลขพุ่งผิดปกติในเสี้ยววินาทีแรก)
        if (totalSeconds >= 1) {
            double elapsedMinutes = elapsedMillis / 60000.0;
            double avgSteps = stepCount / elapsedMinutes;
            double avgShakes = shakeCount / elapsedMinutes;

            tvStepsPerMin.setText(String.format(Locale.getDefault(), "%.1f", avgSteps));
            tvShakesPerMin.setText(String.format(Locale.getDefault(), "%.1f", avgShakes));
        } else {
            tvStepsPerMin.setText("0");
            tvShakesPerMin.setText("0");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isSensorPresent) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (isAccelerometerPresent) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
        }
        // กัน runnable ซ้อนกัน (startNewSession() ก็ post ไว้แล้วตอน onCreate)
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        timerHandler.removeCallbacks(timerRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) dbHelper.close();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // 1. ตรวจสอบและแสดงจำนวนก้าวที่ตรวจจับได้ (Session Delta)
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            lastRawSteps = event.values[0];
            if (initialSteps == -1) {
                initialSteps = lastRawSteps;
            }
            stepCount = (int) (lastRawSteps - initialSteps);
            tvCurrentSteps.setText(String.valueOf(stepCount));
        }

        // 2. ส่วนของการตรวจจับการเขย่า
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            lastAcceleration = currentAcceleration;
            currentAcceleration = (float) Math.sqrt((double) (x * x + y * y + z * z));
            float delta = currentAcceleration - lastAcceleration;
            acceleration = acceleration * 0.9f + delta;

            if (acceleration > SHAKE_THRESHOLD) {
                long currentTime = System.currentTimeMillis();
                if ((currentTime - lastShakeTime) > 400) {
                    lastShakeTime = currentTime;
                    shakeCount++;
                    tvCurrentShakes.setText(String.valueOf(shakeCount));
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // ไม่ได้ใช้งาน
    }
}