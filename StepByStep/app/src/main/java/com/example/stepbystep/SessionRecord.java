package com.example.stepbystep;

public class SessionRecord {
    public final long id;
    public final long startTime;
    public final long endTime;
    public final long durationMs;
    public final int steps;
    public final int shakes;
    public final double stepsPerMin;
    public final double shakesPerMin;
    public final boolean isNewStepRecord;
    public final boolean isNewShakeRecord;

    public SessionRecord(long id, long startTime, long endTime, long durationMs,
                         int steps, int shakes,
                         double stepsPerMin, double shakesPerMin,
                         boolean isNewStepRecord, boolean isNewShakeRecord) {
        this.id = id;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMs = durationMs;
        this.steps = steps;
        this.shakes = shakes;
        this.stepsPerMin = stepsPerMin;
        this.shakesPerMin = shakesPerMin;
        this.isNewStepRecord = isNewStepRecord;
        this.isNewShakeRecord = isNewShakeRecord;
    }
}
