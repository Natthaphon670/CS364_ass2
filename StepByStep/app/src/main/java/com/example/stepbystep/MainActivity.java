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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements SensorEventListener { // เพิ่ม SensorEventListener[cite: 5]

    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private TextView tvCurrentSteps;
    private boolean isSensorPresent;

    // ตัวแปรสำหรับคำนวณก้าว (TYPE_STEP_COUNTER คืนค่าก้าวสะสมตั้งแต่เปิดเครื่อง จึงต้องหาค่าเริ่มต้นมาลบออก)
    private int stepCount = 0;
    private float initialSteps = -1;

    // ตัวแปรสำหรับการเขย่า
    private Sensor accelerometerSensor;
    private TextView tvCurrentShakes;
    private boolean isAccelerometerPresent;
    private int shakeCount = 0;
    private float acceleration = 0f;
    private float currentAcceleration = SensorManager.GRAVITY_EARTH;
    private float lastAcceleration = SensorManager.GRAVITY_EARTH;
    private long lastShakeTime = 0;
    // กำหนดค่าความแรงที่จะนับว่า "เขย่า" (ตัวเลขยิ่งมาก ยิ่งต้องออกแรงเขย่าเยอะ)
    private static final int SHAKE_THRESHOLD = 12;

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

        // เชื่อมต่อ UI กับ TextView tvCurrentSteps
        tvCurrentSteps = findViewById(R.id.tvCurrentSteps);
        tvCurrentShakes = findViewById(R.id.tvCurrentShakes);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ลงทะเบียน Listener เมื่อแอปทำงาน
        if (isSensorPresent) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
        }
        // ลงทะเบียน Listener สำหรับ Accelerometer
        if (isAccelerometerPresent) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ยกเลิกการลงทะเบียน Listener เมื่อแอปอยู่เบื้องหลังเพื่อประหยัดแบตเตอรี่
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // ตรวจสอบและแสดงจำนวนก้าวที่ตรวจจับได้
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            // เก็บค่าก้าวแรกสุดของเซสชันไว้ เพื่อให้จำนวนก้าวเริ่มต้นที่ 0
            if (initialSteps == -1) {
                initialSteps = event.values[0];
            }

            // คำนวณจำนวนก้าวใหม่
            stepCount = (int) (event.values[0] - initialSteps);

            // แสดงผลที่ tvCurrentSteps
            tvCurrentSteps.setText(String.valueOf(stepCount));
        }

        // ส่วนของการตรวจจับการเขย่า
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            lastAcceleration = currentAcceleration;
            // คำนวณหาแรงเหวี่ยงรวม (ตัดค่าแรงโน้มถ่วงออก)
            currentAcceleration = (float) Math.sqrt((double) (x * x + y * y + z * z));
            float delta = currentAcceleration - lastAcceleration;
            acceleration = acceleration * 0.9f + delta;

            // ถ้าแรงเหวี่ยงที่ได้ มากกว่า SHAKE_THRESHOLD จะถือว่ามีการเขย่า
            if (acceleration > SHAKE_THRESHOLD) {
                long currentTime = System.currentTimeMillis();
                // ป้องกันการนับรัวเกินไป (Debounce) เช่น กำหนดให้ห่างกันอย่างน้อย 400 มิลลิวินาที (0.4 วินาที)
                if ((currentTime - lastShakeTime) > 400) {
                    lastShakeTime = currentTime;
                    shakeCount++; // เพิ่มจำนวนการเขย่า
                    tvCurrentShakes.setText(String.valueOf(shakeCount)); // แสดงผลที่หน้าจอ
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // ไม่ได้ใช้งานในส่วนนี้ แต่ต้องมีไว้ตาม interface SensorEventListener
    }
}