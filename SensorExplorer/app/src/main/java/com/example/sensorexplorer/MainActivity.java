package com.example.sensorexplorer;

import android.Manifest;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer, rotationVector, magneticField, lightSensor,
            proximitySensor, stepCounter, stepDetector;
    private TextView tvSensorCount, tvSteps, tvShakes, tvHeading, tvLight, tvBatteryInfo,
            tvPocket, tvPocketProximity, tvPocketLight, tvCompassStatus, tvCompassMessage, tvMagnetic, tvVariation,
            tvAccuracy, tvTestSummary;
    private MaterialCardView cardCompassStatus;
    private RecyclerView sensorRecycler;
    private SensorAdapter sensorAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int shakeCount = 0;
    private float lastStepCounterValue = -1f;
    private int sessionSteps = 0;
    private long lastShakeTime = 0;
    private boolean shakeDialogVisible = false;
    private boolean pocketLikely = false;
    private boolean batteryReceiverRegistered = false;

    private final BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            String technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            int percent = scale > 0 ? Math.round(level * 100f / scale) : 0;

            if (technology == null || technology.trim().isEmpty()) {
                technology = getString(R.string.battery_unknown);
            }

            String temperatureText = temperature >= 0
                    ? getString(R.string.battery_temperature_value, temperature / 10f)
                    : getString(R.string.value_not_available);
            String voltageText = voltage >= 0
                    ? getString(R.string.battery_voltage_value, voltage)
                    : getString(R.string.value_not_available);

            tvBatteryInfo.setText(getString(R.string.battery_info_format,
                    level, scale, percent, technology, temperatureText, voltageText));
        }
    };
    private float currentLight = -1f;
    private float currentProximity = Float.NaN;
    private int compassSecondsRemaining = 8;
    private final Runnable compassCountdown = new Runnable() {
        @Override public void run() {
            if (!compassTestRunning) return;
            compassSecondsRemaining--;
            if (compassSecondsRemaining >= 1) {
                tvTestSummary.setText(getString(R.string.test_running, compassSecondsRemaining));
                handler.postDelayed(this, 1000);
            } else {
                finishCompassTest();
            }
        }
    };
    private float magneticMagnitude = -1f;
    private float azimuth = Float.NaN;
    private float previousAzimuth = Float.NaN;
    private float headingVariation = 0f;
    private float accuracyValue = -1f;
    private final ArrayList<Float> headingSamples = new ArrayList<>();
    private boolean compassTestRunning = false;
    private long compassTestStart = 0;
    private int testSampleCount = 0;
    private float testVariationSum = 0f;
    private float testMagneticMax = 0f;
    private String lastReliabilityReason = "กำลังรอข้อมูลจากเซนเซอร์";

    private final ActivityResultLauncher<String> activityRecognitionPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) registerStepSensors();
                else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        bindViews();
        setupSensorList();
        discoverSensors();
        updatePocketStatus();
        updateCompassUi();

        findViewById(R.id.btnCompassTest).setOnClickListener(v -> startCompassTest());
        findViewById(R.id.btnResetCounters).setOnClickListener(v -> confirmResetCounters());
        findViewById(R.id.btnShowSensors).setOnClickListener(v ->
                sensorRecycler.setVisibility(sensorRecycler.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }//end onCreate

    private void bindViews() {
        tvSensorCount = findViewById(R.id.tvSensorCount);
        tvSteps = findViewById(R.id.tvSteps);
        tvShakes = findViewById(R.id.tvShakes);
        tvHeading = findViewById(R.id.tvHeading);
        tvLight = findViewById(R.id.tvLight);
        tvBatteryInfo = findViewById(R.id.tvBatteryInfo);
        tvPocket = findViewById(R.id.tvPocket);
        tvPocketProximity = findViewById(R.id.tvPocketProximity);
        tvPocketLight = findViewById(R.id.tvPocketLight);
        tvCompassStatus = findViewById(R.id.tvCompassStatus);
        tvCompassMessage = findViewById(R.id.tvCompassMessage);
        tvMagnetic = findViewById(R.id.tvMagnetic);
        tvVariation = findViewById(R.id.tvVariation);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        tvTestSummary = findViewById(R.id.tvTestSummary);
        cardCompassStatus = findViewById(R.id.cardCompassStatus);
        sensorRecycler = findViewById(R.id.sensorRecycler);
    }

    private void setupSensorList() {
        sensorRecycler.setLayoutManager(new LinearLayoutManager(this));
        sensorAdapter = new SensorAdapter();
        sensorRecycler.setAdapter(sensorAdapter);
    }

    private void discoverSensors() {
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tvSensorCount.setText(String.valueOf(sensors.size()));
        sensorAdapter.setSensors(sensors);

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            activityRecognitionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION);
        } else {
            registerStepSensors();
        }
    }

    private void registerStepSensors() {
        if (stepCounter != null) sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        if (stepDetector != null && stepCounter == null)
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!batteryReceiverRegistered) {
            registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
        }
        registerIfAvailable(accelerometer, SensorManager.SENSOR_DELAY_GAME);
        registerIfAvailable(rotationVector, SensorManager.SENSOR_DELAY_UI);
        registerIfAvailable(magneticField, SensorManager.SENSOR_DELAY_UI);
        registerIfAvailable(lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        registerIfAvailable(proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        registerStepSensors();
    }

    private void registerIfAvailable(Sensor sensor, int delay) {
        if (sensor != null)
            sensorManager.registerListener(this, sensor, delay);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryInfoReceiver);
            batteryReceiverRegistered = false;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                detectShake(event.values);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                updateHeading(event.values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magneticMagnitude = (float) Math.sqrt(
                        event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]);
                tvMagnetic.setText(getString(R.string.magnetic_value_format, magneticMagnitude));
                updateCompassUi();
                break;
            case Sensor.TYPE_LIGHT:
                currentLight = event.values[0];
                tvLight.setText(getString(R.string.light_value_format, currentLight));
                tvPocketLight.setText(getString(R.string.light_value_format, currentLight));
                updatePocketStatus();
                updateCompassUi();
                break;
            case Sensor.TYPE_PROXIMITY:
                currentProximity = event.values[0];
                tvPocketProximity.setText(getString(R.string.proximity_value_format, currentProximity));
                float maxRange = event.sensor.getMaximumRange();
                boolean near = currentProximity < Math.min(maxRange, 5.0f);
                // Pocket status is a heuristic, not a definitive determination.
                pocketLikely = near && (currentLight >= 0 && currentLight < 40f);
                updatePocketStatus();
                break;
            case Sensor.TYPE_STEP_COUNTER:
                if (event.values.length > 0) {
                    if (lastStepCounterValue < 0) lastStepCounterValue = event.values[0];
                    sessionSteps = Math.max(0, Math.round(event.values[0] - lastStepCounterValue));
                    tvSteps.setText(String.valueOf(sessionSteps));
                }
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                sessionSteps++;
                tvSteps.setText(String.valueOf(sessionSteps));
                break;
        }
    }

    private void detectShake(float[] values) {
        float x = values[0], y = values[1], z = values[2];
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        long now = System.currentTimeMillis();
        // Approximate threshold; tune with real devices to reduce false positives.
        if (Math.abs(magnitude - SensorManager.GRAVITY_EARTH) > 3.2f && now - lastShakeTime > 700) {
            lastShakeTime = now;
            shakeCount++;
            tvShakes.setText(String.valueOf(shakeCount));
            showShakeDialog();
        }
    }

    private void showShakeDialog() {
        if (isFinishing() || shakeDialogVisible) return;
        shakeDialogVisible = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.shake_dialog_title)
                .setMessage(getString(R.string.shake_dialog_message, shakeCount))
                .setCancelable(false)
                .setPositiveButton(R.string.acknowledge, (dialog, which) -> {
                    shakeDialogVisible = false;
                    dialog.dismiss();
                })
                .setOnDismissListener(dialog -> shakeDialogVisible = false)
                .show();
    }

    private void updateHeading(float[] values) {
        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        float newAzimuth = (float) Math.toDegrees(orientation[0]);
        newAzimuth = (newAzimuth + 360f) % 360f;

        if (!Float.isNaN(previousAzimuth)) {
            float diff = Math.abs(newAzimuth - previousAzimuth);
            diff = Math.min(diff, 360f - diff);
            headingVariation = diff;
        }
        previousAzimuth = newAzimuth;
        azimuth = newAzimuth;
        headingSamples.add(azimuth);
        if (headingSamples.size() > 12) headingSamples.remove(0);

        if (compassTestRunning) {
            testSampleCount++;
            testVariationSum += headingVariation;
            if (magneticMagnitude > testMagneticMax) testMagneticMax = magneticMagnitude;
        }
        updateCompassUi();
    }

    private void updateCompassUi() {
        if (Float.isNaN(azimuth)) {
            tvHeading.setText(R.string.heading_placeholder);
            setCompassStatus("UNRELIABLE", getString(R.string.compass_waiting_message), 2);
            return;
        }

        tvHeading.setText(getString(R.string.heading_value_format, azimuth, cardinalDirection(azimuth)));
        tvVariation.setText(getString(R.string.variation_value_format, headingVariation));
        updatePocketStatus();

        if (rotationVector == null) {
            setCompassStatus("UNRELIABLE", getString(R.string.no_rotation_sensor), 2);
            return;
        }
        if (magneticField == null) {
            setCompassStatus("WARNING", getString(R.string.no_magnetic_sensor), 1);
            return;
        }
        if (magneticMagnitude > 100f) {
            setCompassStatus("WARNING", getString(R.string.magnetic_high_warning), 1);
            return;
        }
        if (headingVariation > 12f) {
            setCompassStatus("WARNING", getString(R.string.heading_unstable_warning), 1);
            return;
        }
        setCompassStatus("GOOD", getString(R.string.heading_stable_message), 0);
    }

    private String cardinalDirection(float degrees) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = Math.round(degrees / 45f) % 8;
        return directions[index];
    }

    private void setCompassStatus(String status, String message, int level) {
        tvCompassStatus.setText(level == 0 ? R.string.status_good
                : level == 1 ? R.string.status_warning : R.string.status_unreliable);
        tvCompassMessage.setText(message);
        int color = level == 0 ? getColorCompat(R.color.status_good)
                : level == 1 ? getColorCompat(R.color.status_warning)
                  : getColorCompat(R.color.status_bad);
        cardCompassStatus.setCardBackgroundColor(color);
        tvCompassStatus.setTextColor(getColorCompat(R.color.white));
        tvCompassMessage.setTextColor(getColorCompat(R.color.white));
        lastReliabilityReason = message;
        if (accuracyValue >= 0) {
            tvAccuracy.setText(getString(R.string.android_accuracy_format, accuracyLabel(accuracyValue)));
        }
    }

    private String accuracyLabel(float accuracy) {
        if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return getString(R.string.accuracy_high);
        if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return getString(R.string.accuracy_medium);
        if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return getString(R.string.accuracy_low);
        return getString(R.string.accuracy_unreliable);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD ||
                sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            accuracyValue = accuracy;
            tvAccuracy.setText(getString(R.string.android_accuracy_format, accuracyLabel(accuracy)));
            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                setCompassStatus("UNRELIABLE", getString(R.string.android_unreliable), 2);
            } else if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                setCompassStatus("WARNING", getString(R.string.android_accuracy_low), 1);
            }
        }
    }

    private void updatePocketStatus() {
        if (currentLight < 0) {
            tvPocket.setText(R.string.waiting_light);
            tvPocketLight.setText(R.string.value_not_available);
        } else if (pocketLikely) {
            tvPocket.setText(R.string.pocket_likely);
        } else {
            tvPocket.setText(R.string.pocket_not_confirmed);
        }
        if (Float.isNaN(currentProximity)) tvPocketProximity.setText(R.string.value_not_available);
    }

    private void startCompassTest() {
        if (rotationVector == null) {
            tvTestSummary.setText(R.string.test_unavailable);
            return;
        }
        compassTestRunning = true;
        compassTestStart = System.currentTimeMillis();
        testSampleCount = 0;
        testVariationSum = 0f;
        testMagneticMax = Math.max(0, magneticMagnitude);
        handler.removeCallbacks(compassCountdown);
        compassSecondsRemaining = 8;
        tvTestSummary.setText(getString(R.string.test_running, compassSecondsRemaining));
        handler.postDelayed(compassCountdown, 1000);
    }

    private void finishCompassTest() {
        if (!compassTestRunning) return;
        compassTestRunning = false;
        handler.removeCallbacks(compassCountdown);
        float averageVariation = testSampleCount > 0 ? testVariationSum / testSampleCount : 0f;
        String result = getString(R.string.test_result_title) + "\n" +
                getString(R.string.test_samples, testSampleCount) + "\n" +
                getString(R.string.test_average_variation,
                        String.format(Locale.getDefault(), "%.1f°", averageVariation)) + "\n" +
                getString(R.string.test_max_magnetic,
                        String.format(Locale.getDefault(), "%.1f µT", testMagneticMax)) + "\n" +
                getString(R.string.test_current_status, tvCompassStatus.getText());
        tvTestSummary.setText(result);
        new AlertDialog.Builder(this)
                .setTitle(R.string.compass_report_title)
                .setMessage(result)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void confirmResetCounters() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm, (dialog, which) -> resetCounters())
                .show();
    }

    private void resetCounters() {
        shakeCount = 0;
        sessionSteps = 0;
        lastStepCounterValue = -1f;
        tvShakes.setText(R.string.zero);
        tvSteps.setText(R.string.zero);
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }

    private int getColorCompat(int id) {
        return ContextCompat.getColor(this, id);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static class SensorAdapter extends RecyclerView.Adapter<SensorAdapter.SensorViewHolder> {
        private final List<Sensor> sensors = new ArrayList<>();
        void setSensors(List<Sensor> items) {
            sensors.clear();
            sensors.addAll(items);
            notifyDataSetChanged();
        }
        @Override public SensorViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sensor, parent, false);
            return new SensorViewHolder(view);
        }
        @Override public void onBindViewHolder(SensorViewHolder holder, int position) {
            Sensor sensor = sensors.get(position);
            holder.name.setText(sensor.getName());
            holder.detail.setText(sensorTypeName(sensor.getType(), holder) + " • " + sensor.getVendor() +
                    "\n" + holder.itemView.getContext().getString(R.string.sensor_detail_format, sensor.getResolution(), sensor.getMaximumRange()));
        }
        @Override public int getItemCount() { return sensors.size(); }
        static String sensorTypeName(int type, SensorViewHolder holder) {
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER: return holder.itemView.getContext().getString(R.string.sensor_type_accelerometer);
                case Sensor.TYPE_MAGNETIC_FIELD: return holder.itemView.getContext().getString(R.string.sensor_type_magnetic_field);
                case Sensor.TYPE_ORIENTATION: return holder.itemView.getContext().getString(R.string.sensor_type_orientation);
                case Sensor.TYPE_GYROSCOPE: return holder.itemView.getContext().getString(R.string.sensor_type_gyroscope);
                case Sensor.TYPE_LIGHT: return holder.itemView.getContext().getString(R.string.sensor_type_light);
                case Sensor.TYPE_PROXIMITY: return holder.itemView.getContext().getString(R.string.sensor_type_proximity);
                case Sensor.TYPE_GRAVITY: return holder.itemView.getContext().getString(R.string.sensor_type_gravity);
                case Sensor.TYPE_LINEAR_ACCELERATION: return holder.itemView.getContext().getString(R.string.sensor_type_linear_acceleration);
                case Sensor.TYPE_ROTATION_VECTOR: return holder.itemView.getContext().getString(R.string.sensor_type_rotation_vector);
                case Sensor.TYPE_RELATIVE_HUMIDITY: return holder.itemView.getContext().getString(R.string.sensor_type_humidity);
                case Sensor.TYPE_AMBIENT_TEMPERATURE: return holder.itemView.getContext().getString(R.string.sensor_type_temperature);
                case Sensor.TYPE_STEP_COUNTER: return holder.itemView.getContext().getString(R.string.sensor_type_step_counter);
                case Sensor.TYPE_STEP_DETECTOR: return holder.itemView.getContext().getString(R.string.sensor_type_step_detector);
                case Sensor.TYPE_SIGNIFICANT_MOTION: return holder.itemView.getContext().getString(R.string.sensor_type_significant_motion);
                case Sensor.TYPE_GAME_ROTATION_VECTOR: return holder.itemView.getContext().getString(R.string.sensor_type_game_rotation_vector);
                case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: return holder.itemView.getContext().getString(R.string.sensor_type_geomagnetic_rotation_vector);
                case Sensor.TYPE_HEART_RATE: return holder.itemView.getContext().getString(R.string.sensor_type_heart_rate);
                default: return holder.itemView.getContext().getString(R.string.sensor_type_format, type);
            }
        }
        static class SensorViewHolder extends RecyclerView.ViewHolder {
            TextView name, detail;
            SensorViewHolder(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tvSensorName);
                detail = itemView.findViewById(R.id.tvSensorDetail);
            }
        }
    }
}
